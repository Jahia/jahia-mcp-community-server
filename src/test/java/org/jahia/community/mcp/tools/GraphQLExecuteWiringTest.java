package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jahia.community.mcp.access.GraphQLWhitelistGate;
import org.jahia.community.mcp.config.McpConfigService;
import org.jahia.community.mcp.config.WhitelistSnapshot;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The REAL gate, execute tool and in-process client wired together; only the GraphQL servlet is
 * stubbed. Proves that what the gate approves is byte-for-byte what reaches the servlet, and that a
 * refusal never reaches it.
 */
@DisplayName("community.graphql.execute: gate + tool + client wired together")
class GraphQLExecuteWiringTest {

    private static final String ALLOWED = "{ jcr { nodeByPath(path: \"/\") { name } } }";
    private static final String DELETE = "mutation { jcr { deleteNode(pathOrId: \"/sites/x\") } }";

    private final List<JsonNode> received = new ArrayList<>();
    private String responseBody = "{\"data\":{}}";
    private McpConfigService config;
    private GraphQLWhitelistGate gate;
    private GraphQLExecuteTool tool;

    @BeforeEach
    void setUp() {
        received.clear();
        config = mock(McpConfigService.class);
        configure();
        gate = spy(new GraphQLWhitelistGate(config));
        final HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(final HttpServletRequest req, final HttpServletResponse resp) throws IOException {
                received.add(ToolSupport.MAPPER.readTree(req.getReader().lines().collect(Collectors.joining("\n"))));
                resp.getWriter().write(responseBody);
            }
        };
        tool = new GraphQLExecuteTool(new InternalGraphQLClient(servlet), gate);
    }

    private void configure(final String... entries) {
        when(config.snapshot()).thenReturn(WhitelistSnapshot.of(Set.of(entries)));
    }

    private static ObjectNode input(final String query) {
        return ToolSupport.MAPPER.createObjectNode().put("query", query);
    }

    private ToolResult run(final ObjectNode in, final ToolContextFixture f) {
        return tool.execute(in, f.ctx);
    }

    @Test
    @DisplayName("an allowed query reaches the servlet exactly once, byte for byte, gated for the caller and client address")
    void allowed_query_is_executed_unchanged() {
        final ToolContextFixture f = ToolContextFixture.administrator();
        final String query = "  query   Q  { jcr { nodeByPath(path: \"/\") { name } } }\n# comment\n";

        final ToolResult result = run(input(query), f);

        assertThat(result.isOk()).isTrue();
        assertThat(received).hasSize(1);
        assertThat(received.get(0).path("query").asText()).isEqualTo(query);
        verify(gate).evaluate(query, "root", "10.0.0.7");
    }

    @Test
    @DisplayName("a refused mutation never reaches the servlet and carries the typed hint")
    void refused_mutation_never_reaches_the_servlet() {
        final ToolResult result = run(input(DELETE), ToolContextFixture.administrator());

        assertThat(result.error().code()).isEqualTo("forbidden");
        assertThat(result.error().hint()).contains("'mutation:<field>' entry");
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("every operation of a multi-operation document is gated, whatever operationName says")
    void operation_name_cannot_select_around_the_gate() {
        final String doc = "query Safe " + ALLOWED.substring(0) + " mutation Danger { jcr { deleteNode(pathOrId: \"/\") } }";
        final ObjectNode in = input(doc).put("operationName", "Safe");

        final ToolResult result = run(in, ToolContextFixture.administrator());

        assertThat(result.error().code()).isEqualTo("forbidden");
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("operationName is not forwarded: the servlet receives exactly the gated query and variables")
    void only_query_and_variables_are_forwarded() {
        final ObjectNode in = input(ALLOWED).put("operationName", "Q");
        in.putObject("variables").put("v", 1);

        run(in, ToolContextFixture.administrator());

        assertThat(received).hasSize(1);
        assertThat(received.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("query", "variables");
    }

    @Test
    @DisplayName("variables are data: a string that looks like a mutation neither blocks nor widens anything")
    void variables_are_not_inspected_by_the_gate() {
        final ObjectNode in = input("query($p: String!) { jcr { nodeByPath(path: $p) { name } } }");
        in.putObject("variables").put("p", DELETE);

        assertThat(run(in, ToolContextFixture.administrator()).isOk()).isTrue();
        assertThat(received.get(0).path("variables").path("p").asText()).isEqualTo(DELETE);
    }

    @Test
    @DisplayName("a role grant is refused even under a broad mutation entry")
    void role_grant_is_refused_under_broad_entries() {
        configure("mutation:jcr");

        final ToolResult result = run(input("mutation { jcr { mutateNode(pathOrId: \"/\") { grantRoles(principalType: USER, "
                + "principalName: \"a\", roleNames: [\"server-administrator\"]) } } }"), ToolContextFixture.administrator());

        assertThat(result.error().code()).isEqualTo("forbidden");
        assertThat(result.error().message()).contains("can never be run");
        assertThat(result.error().hint()).contains("Do not retry");
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("a session-only administrator call is refused before the gate or the servlet")
    void session_only_call_is_refused_first() {
        final ToolContextFixture f = ToolContextFixture.administrator();
        when(f.request.getHeader("Authorization")).thenReturn(null);

        final ToolResult result = run(input(ALLOWED), f);

        assertThat(result.error().code()).isEqualTo("forbidden");
        assertThat(result.error().message()).contains("explicit Authorization header");
        assertThat(received).isEmpty();
        org.mockito.Mockito.verify(gate, org.mockito.Mockito.never()).evaluate(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("a syntax error is a validation error with the position, not a forbidden refusal")
    void syntax_error_is_a_validation_error_with_position() {
        final ToolResult result = run(input("{ jcr {\n nodeByPath(path: ) }"), ToolContextFixture.administrator());

        assertThat(result.error().code()).isEqualTo("validation");
        assertThat(result.error().message()).contains("syntax error at line 2");
        assertThat(received).isEmpty();
    }

    @Test
    @DisplayName("an oversized response is a non-retryable validation error telling the caller to narrow the query")
    void oversized_response_end_to_end() {
        responseBody = "a".repeat(InternalGraphQLClient.MAX_RESPONSE_BYTES + 10);

        final ToolResult result = run(input(ALLOWED), ToolContextFixture.administrator());

        assertThat(result.error().code()).isEqualTo("validation");
        assertThat(result.error().hint()).contains("narrow the query").contains("Do not retry the same document");
    }
}
