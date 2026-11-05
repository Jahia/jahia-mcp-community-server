package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.jahia.community.mcp.access.CommunityMcpAccess;
import org.jahia.community.mcp.access.GraphQLWhitelistGate;
import org.jahia.modules.mcp.spi.tool.JahiaMcpTool;
import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.modules.mcp.spi.tool.ToolError;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.jahia.modules.mcp.spi.tool.ToolSafety;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * {@code community.graphql.execute} — runs any GraphQL query or mutation against Jahia's
 * graphql-dxm-provider as the calling administrator, subject to the configured whitelist.
 */
@Component(service = JahiaMcpTool.class, immediate = true)
public class GraphQLExecuteTool implements JahiaMcpTool {

    static final String NAME = "community.graphql.execute";
    private static final Logger LOGGER = LoggerFactory.getLogger(GraphQLExecuteTool.class);
    private static final JsonNode SCHEMA = ToolSupport.loadSchema(NAME);
    private static final int MAX_ERROR_MESSAGE_CHARS = 500;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_UNAUTHORIZED = 401;
    private static final int HTTP_FORBIDDEN = 403;

    private volatile InternalGraphQLClient graphQLClient;
    private volatile GraphQLWhitelistGate whitelistGate;

    @Reference
    public void setGraphQLClient(final InternalGraphQLClient graphQLClient) {
        this.graphQLClient = graphQLClient;
    }

    @Reference
    public void setWhitelistGate(final GraphQLWhitelistGate whitelistGate) {
        this.whitelistGate = whitelistGate;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Execute any GraphQL query or mutation against Jahia's GraphQL API as the calling "
                + "administrator, subject to the configured operation whitelist. Prefer the dedicated "
                + "site/page/content tools when one fits; call community.graphql.introspect first to "
                + "discover available operations and arguments.";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSafety safety() {
        // Can run arbitrary mutations (delete, publish, admin operations).
        return ToolSafety.DESTRUCTIVE;
    }

    @Override
    public Set<String> tags() {
        return Set.of("destructive", "reads-ugc", "community");
    }

    @Override
    public ToolResult execute(final JsonNode input, final ToolContext ctx) {
        final JsonNode query = input.path("query");
        // Identify the document in the audit trail by hash only: never the text or the variables,
        // which may carry content or secrets.
        final Map<String, Object> extra = query.isTextual()
                ? Map.of("queryHash", ToolSupport.sha256Hex(query.asText()))
                : Map.of();
        return ToolSupport.audited(ctx, NAME, run(input, ctx), extra);
    }

    private ToolResult run(final JsonNode input, final ToolContext ctx) {
        final Optional<ToolResult> denied = CommunityMcpAccess.requireAdministrator(ctx);
        if (denied.isPresent()) {
            return denied.get();
        }

        final JsonNode queryNode = input.path("query");
        if (!queryNode.isTextual() || queryNode.asText().isBlank()) {
            return ToolResult.error(ToolError.CODE_VALIDATION,
                    "'query' is required and must be a non-blank string.",
                    "Pass a GraphQL document in 'query'.");
        }
        final JsonNode variablesNode = input.path("variables");
        if (!variablesNode.isMissingNode() && !variablesNode.isNull() && !variablesNode.isObject()) {
            return ToolResult.error(ToolError.CODE_VALIDATION,
                    "'variables' must be a JSON object.",
                    "Pass variables as an object keyed by variable name, or omit it.");
        }
        final String query = queryNode.asText();

        final Optional<String> blocked = whitelistGate.check(query, ctx.username(), CommunityMcpAccess.clientIp(ctx));
        if (blocked.isPresent()) {
            return ToolResult.error(ToolError.CODE_FORBIDDEN, blocked.get(),
                    "Only whitelisted GraphQL operations may run; an administrator can extend the "
                            + "whitelist in Administration -> MCP Server.");
        }

        final Object variables = variablesNode.isObject()
                ? ToolSupport.MAPPER.convertValue(variablesNode, Map.class)
                : null;
        try {
            return toResult(graphQLClient.execute(query, variables, ToolSupport.authorization(ctx)));
        } catch (IOException | ServletException | RuntimeException ex) {
            LOGGER.error("{} failed for user='{}'", NAME, ctx.username(), ex);
            return ToolResult.error(ToolError.CODE_INTERNAL, "The GraphQL operation could not be executed.",
                    "Retry; if the failure persists, check the Jahia logs.");
        }
    }

    private static ToolResult toResult(final InternalGraphQLClient.Response response) {
        final JsonNode json = ToolSupport.parseJsonOrNull(response.body());
        if (!response.isHttpError()) {
            // GraphQL-level errors arrive with HTTP 200 inside "errors" — returned as-is for the model.
            return ToolResult.ok(json != null ? json : TextNode.valueOf(response.body()));
        }
        final String message = "GraphQL endpoint returned HTTP " + response.status() + ": " + errorMessages(json);
        return ToolResult.error(codeFor(response.status()), truncate(message),
                "Fix the GraphQL document (check names with community.graphql.introspect) and retry.");
    }

    private static String errorMessages(final JsonNode json) {
        if (json == null || !json.path("errors").isArray() || json.path("errors").isEmpty()) {
            return "no error details";
        }
        final StringBuilder sb = new StringBuilder();
        for (final JsonNode error : json.path("errors")) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(error.path("message").asText("unknown error"));
        }
        return sb.toString();
    }

    private static String codeFor(final int status) {
        if (status == HTTP_BAD_REQUEST) {
            return ToolError.CODE_VALIDATION;
        }
        if (status == HTTP_UNAUTHORIZED || status == HTTP_FORBIDDEN) {
            return ToolError.CODE_FORBIDDEN;
        }
        return ToolError.CODE_INTERNAL;
    }

    private static String truncate(final String s) {
        return s.length() <= MAX_ERROR_MESSAGE_CHARS ? s : s.substring(0, MAX_ERROR_MESSAGE_CHARS) + "…";
    }
}
