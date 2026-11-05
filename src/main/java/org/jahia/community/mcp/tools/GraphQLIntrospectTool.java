package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jahia.community.mcp.access.CommunityMcpAccess;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * {@code community.graphql.introspect} — describes the GraphQL schema in several requests, each
 * with at most one {@code __Type.fields} selection, to stay under Jahia's bad-faith introspection
 * guard (which rejects documents with more than one).
 */
@Component(service = JahiaMcpTool.class, immediate = true)
public class GraphQLIntrospectTool implements JahiaMcpTool {

    static final String NAME = "community.graphql.introspect";
    private static final Logger LOGGER = LoggerFactory.getLogger(GraphQLIntrospectTool.class);
    private static final JsonNode SCHEMA = ToolSupport.loadSchema(NAME);

    // Step 1: type names + root types — zero __Type.fields selections.
    private static final String STEP1 = "{ __schema { queryType { name } mutationType { name } "
            + "subscriptionType { name } types { name kind description } } }";

    // Step 2: one request per type — exactly one __Type.fields selection.
    private static final String TYPE_QUERY = "{ __type(name: \"%s\") { name kind description "
            + "fields(includeDeprecated: false) { name description "
            + "args { name description type { name kind ofType { name kind ofType { name kind } } } } "
            + "type { name kind ofType { name kind ofType { name kind } } } } "
            + "inputFields { name description type { name kind ofType { name kind ofType { name kind } } } } "
            + "enumValues(includeDeprecated: false) { name description } "
            + "interfaces { name kind } possibleTypes { name kind } } }";

    private volatile InternalGraphQLClient graphQLClient;

    @Reference
    public void setGraphQLClient(final InternalGraphQLClient graphQLClient) {
        this.graphQLClient = graphQLClient;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Describe Jahia's GraphQL schema: root query/mutation types and full details of every "
                + "named type. Call this before community.graphql.execute. The result is large.";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSafety safety() {
        return ToolSafety.READ_ONLY;
    }

    @Override
    public Set<String> tags() {
        return Set.of("community");
    }

    @Override
    public ToolResult execute(final JsonNode input, final ToolContext ctx) {
        return ToolSupport.audited(ctx, NAME, run(ctx));
    }

    private ToolResult run(final ToolContext ctx) {
        final Optional<ToolResult> denied = CommunityMcpAccess.requireAdministrator(ctx);
        if (denied.isPresent()) {
            return denied.get();
        }
        final String auth = ToolSupport.authorization(ctx);
        try {
            final InternalGraphQLClient.Response step1Response = graphQLClient.execute(STEP1, null, auth);
            final JsonNode step1 = step1Response.isHttpError() ? null : ToolSupport.parseJsonOrNull(step1Response.body());
            final JsonNode schema = step1 == null ? null : step1.path("data").path("__schema");
            if (schema == null || !schema.isObject()) {
                LOGGER.error("{}: schema step returned no data (HTTP {})", NAME, step1Response.status());
                return internalError();
            }
            final ObjectNode result = ToolSupport.MAPPER.createObjectNode();
            result.set("schema", schema);
            final ObjectNode types = result.putObject("types");
            final ArrayNode skipped = result.putArray("skippedTypes");
            fetchTypeDetails(collectTypeNames(schema.path("types")), auth, types, skipped);
            if (!skipped.isEmpty()) {
                LOGGER.warn("{}: {} type(s) could not be introspected: {}", NAME, skipped.size(), skipped);
            }
            return ToolResult.ok(result);
        } catch (IOException | ServletException | RuntimeException ex) {
            LOGGER.error("{} failed for user='{}'", NAME, ctx.username(), ex);
            return internalError();
        }
    }

    private static List<String> collectTypeNames(final JsonNode types) {
        final List<String> names = new ArrayList<>();
        for (final JsonNode t : types) {
            final String name = t.path("name").asText("");
            if (!name.isEmpty() && !name.startsWith("__") && !"SCALAR".equals(t.path("kind").asText(""))) {
                names.add(name);
            }
        }
        return names;
    }

    /** Fills {@code details} per type; types that cannot be fetched are named in {@code skipped}. */
    private void fetchTypeDetails(final List<String> typeNames, final String auth,
            final ObjectNode details, final ArrayNode skipped) throws IOException, ServletException {
        for (final String typeName : typeNames) {
            // typeName comes from the server's own schema (GraphQL names are [_A-Za-z][_0-9A-Za-z]*).
            final InternalGraphQLClient.Response response =
                    graphQLClient.execute(String.format(TYPE_QUERY, typeName), null, auth);
            final JsonNode json = response.isHttpError() ? null : ToolSupport.parseJsonOrNull(response.body());
            final JsonNode type = json == null ? null : json.path("data").path("__type");
            if (type != null && type.isObject()) {
                details.set(typeName, type);
            } else {
                skipped.add(typeName);
            }
        }
    }

    private static ToolResult internalError() {
        return ToolResult.error(ToolError.CODE_INTERNAL, "The GraphQL schema could not be introspected.",
                "Retry; if the failure persists, check that graphql-dxm-provider is started.");
    }
}
