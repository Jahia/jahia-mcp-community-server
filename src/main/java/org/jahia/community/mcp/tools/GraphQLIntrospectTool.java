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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code community.graphql.introspect} — bounded, two-level schema discovery.
 *
 * <ul>
 *   <li>No arguments: the type list (name + kind) and full details of the root Query/Mutation types.</li>
 *   <li>{@code typeNames}: full details of up to {@value #MAX_TYPE_NAMES} named types.</li>
 * </ul>
 *
 * <p>Each type is fetched in its own request (Jahia's bad-faith introspection guard rejects documents
 * with more than one {@code __Type.fields} selection), with the name passed as a GraphQL variable.
 * Details stop once the result reaches {@value #MAX_RESULT_CHARS} characters; the remaining names
 * are returned in {@code truncatedTypes} so the caller can ask for them in a later call.</p>
 */
@Component(service = JahiaMcpTool.class, immediate = true)
public class GraphQLIntrospectTool implements JahiaMcpTool {

    static final String NAME = "community.graphql.introspect";
    static final int MAX_TYPE_NAMES = 25;
    static final int MAX_RESULT_CHARS = 100_000;
    private static final Logger LOGGER = LoggerFactory.getLogger(GraphQLIntrospectTool.class);
    private static final JsonNode SCHEMA = ToolSupport.loadSchema(NAME);
    private static final Pattern GRAPHQL_NAME = Pattern.compile("[_A-Za-z][_0-9A-Za-z]*");

    // Type list + root type names — zero __Type.fields selections.
    private static final String SCHEMA_QUERY = "{ __schema { queryType { name } mutationType { name } "
            + "subscriptionType { name } types { name kind } } }";

    // One type — exactly one __Type.fields selection; the name is a variable, never formatted in.
    private static final String TYPE_QUERY = "query($name: String!) { __type(name: $name) { name kind description "
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
        return "Discover Jahia's GraphQL schema before calling community.graphql.execute. Without arguments, "
                + "returns every type name and kind plus the fields of the root Query and Mutation types. "
                + "Pass typeNames (at most " + MAX_TYPE_NAMES + ") to get the fields, arguments and enum values "
                + "of specific types; names left in truncatedTypes can be requested in a further call.";
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
        return ToolSupport.audited(ctx, NAME, run(input, ctx));
    }

    private ToolResult run(final JsonNode input, final ToolContext ctx) {
        final Optional<ToolResult> denied = CommunityMcpAccess.requireAdministrator(ctx);
        if (denied.isPresent()) {
            return denied.get();
        }
        final JsonNode typeNamesNode = input.path("typeNames");
        final boolean explicit = !typeNamesNode.isMissingNode() && !typeNamesNode.isNull();
        final Optional<ToolResult> invalid = explicit ? validateTypeNames(typeNamesNode) : Optional.empty();
        if (invalid.isPresent()) {
            return invalid.get();
        }
        final String auth = ToolSupport.authorization(ctx);
        try {
            final ObjectNode result = ToolSupport.MAPPER.createObjectNode();
            final List<String> wanted = new ArrayList<>();
            if (explicit) {
                final Set<String> distinct = new LinkedHashSet<>();
                typeNamesNode.forEach(n -> distinct.add(n.asText()));
                wanted.addAll(distinct);
            } else {
                final JsonNode schema = fetchSchemaSummary(auth);
                if (schema == null) {
                    return internalError();
                }
                result.set("schema", schema);
                addRootTypeName(schema, "queryType", wanted);
                addRootTypeName(schema, "mutationType", wanted);
            }
            fetchTypeDetails(wanted, auth, result);
            return ToolResult.ok(result);
        } catch (IOException | ServletException | RuntimeException ex) {
            LOGGER.error("{} failed for user='{}'", NAME, ctx.username(), ex);
            return internalError();
        }
    }

    private static Optional<ToolResult> validateTypeNames(final JsonNode typeNames) {
        if (!typeNames.isArray() || typeNames.isEmpty() || typeNames.size() > MAX_TYPE_NAMES) {
            return Optional.of(ToolResult.error(ToolError.CODE_VALIDATION,
                    "'typeNames' must be an array of 1 to " + MAX_TYPE_NAMES + " type names.",
                    "Request fewer types per call, or omit typeNames to get the type list."));
        }
        for (final JsonNode n : typeNames) {
            if (!n.isTextual() || !GRAPHQL_NAME.matcher(n.asText()).matches()) {
                return Optional.of(ToolResult.error(ToolError.CODE_VALIDATION,
                        "'typeNames' entries must be GraphQL type names.",
                        "Use names exactly as returned in schema.types by a call without arguments."));
            }
        }
        return Optional.empty();
    }

    /** The {@code __schema} summary, type list reduced to non-introspection {name, kind}; null on failure. */
    private JsonNode fetchSchemaSummary(final String auth) throws IOException, ServletException {
        final InternalGraphQLClient.Response response = graphQLClient.execute(SCHEMA_QUERY, null, auth);
        final JsonNode json = response.isHttpError() ? null : ToolSupport.parseJsonOrNull(response.body());
        final JsonNode schema = json == null ? null : json.path("data").path("__schema");
        if (schema == null || !schema.isObject()) {
            LOGGER.error("{}: schema step returned no data (HTTP {})", NAME, response.status());
            return null;
        }
        final ObjectNode summary = ((ObjectNode) schema).deepCopy();
        final ArrayNode types = summary.putArray("types");
        for (final JsonNode t : schema.path("types")) {
            final String name = t.path("name").asText("");
            if (!name.isEmpty() && !name.startsWith("__")) {
                types.addObject().put("name", name).put("kind", t.path("kind").asText(""));
            }
        }
        return summary;
    }

    private static void addRootTypeName(final JsonNode schema, final String field, final List<String> into) {
        final String name = schema.path(field).path("name").asText("");
        if (!name.isEmpty()) {
            into.add(name);
        }
    }

    /**
     * Adds {@code types}, {@code skippedTypes} (unknown or failed) and {@code truncatedTypes} (not
     * fetched: output budget reached) to {@code result}. The budget counts type details only — the
     * compact {name, kind} list must never push the root types out. The first type is always
     * included so a caller asking for one large type still makes progress.
     */
    private void fetchTypeDetails(final List<String> names, final String auth, final ObjectNode result)
            throws IOException, ServletException {
        final ObjectNode details = result.putObject("types");
        final ArrayNode skipped = result.putArray("skippedTypes");
        final ArrayNode truncated = result.putArray("truncatedTypes");
        int used = 0;
        for (int i = 0; i < names.size(); i++) {
            final String name = names.get(i);
            if (used >= MAX_RESULT_CHARS && !details.isEmpty()) {
                names.subList(i, names.size()).forEach(truncated::add);
                break;
            }
            final InternalGraphQLClient.Response response = graphQLClient.execute(TYPE_QUERY, Map.of("name", name), auth);
            final JsonNode json = response.isHttpError() ? null : ToolSupport.parseJsonOrNull(response.body());
            final JsonNode type = json == null ? null : json.path("data").path("__type");
            if (type == null || !type.isObject()) {
                skipped.add(name);
                continue;
            }
            final int size = type.toString().length();
            if (used + size > MAX_RESULT_CHARS && !details.isEmpty()) {
                names.subList(i, names.size()).forEach(truncated::add);
                break;
            }
            details.set(name, type);
            used += size;
        }
        if (!skipped.isEmpty() || !truncated.isEmpty()) {
            LOGGER.info("{}: skipped={}, truncated={}", NAME, skipped, truncated);
        }
    }

    private static ToolResult internalError() {
        return ToolResult.error(ToolError.CODE_INTERNAL, "The GraphQL schema could not be introspected.",
                "Retry; if the failure persists, check that graphql-dxm-provider is started.");
    }
}
