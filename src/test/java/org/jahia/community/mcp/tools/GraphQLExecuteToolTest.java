package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jahia.community.mcp.access.GraphQLWhitelistGate;
import org.jahia.modules.mcp.spi.tool.ToolError;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.jahia.modules.mcp.spi.tool.ToolSafety;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphQLExecuteToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InternalGraphQLClient client;
    private GraphQLWhitelistGate gate;
    private GraphQLExecuteTool tool;

    @BeforeEach
    void setUp() {
        client = mock(InternalGraphQLClient.class);
        gate = mock(GraphQLWhitelistGate.class);
        when(gate.check(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        tool = new GraphQLExecuteTool(client, gate);
    }

    private static ObjectNode input(final String query) {
        ObjectNode in = MAPPER.createObjectNode();
        if (query != null) {
            in.put("query", query);
        }
        return in;
    }

    @Test
    @DisplayName("contract: namespaced name, DESTRUCTIVE safety, schema requires query")
    void contract() {
        assertThat(tool.name()).isEqualTo("community.graphql.execute");
        assertThat(tool.safety()).isEqualTo(ToolSafety.DESTRUCTIVE);
        assertThat(tool.inputSchema().path("required").toString()).contains("query");
        assertThat(tool.description()).isNotBlank();
    }

    @Test
    @DisplayName("non-administrator is refused before the gate or GraphQL is touched")
    void non_admin_forbidden() throws Exception {
        ToolContextFixture f = ToolContextFixture.nonAdministrator();

        ToolResult result = tool.execute(input("{ currentUser { name } }"), f.ctx);

        assertThat(result.error().code()).isEqualTo(ToolError.CODE_FORBIDDEN);
        verify(client, never()).execute(any(), anyString(), any());
        assertThat(f.auditEvents).containsExactly("tool.execute.error");
    }

    @Test
    @DisplayName("missing or blank query → validation error")
    void blank_query_is_validation_error() {
        ToolContextFixture f = ToolContextFixture.administrator();

        assertThat(tool.execute(input(null), f.ctx).error().code()).isEqualTo(ToolError.CODE_VALIDATION);
        assertThat(tool.execute(input("  "), f.ctx).error().code()).isEqualTo(ToolError.CODE_VALIDATION);
    }

    @Test
    @DisplayName("whitelist denial → forbidden error carrying the gate's reason, GraphQL not called")
    void whitelist_denial() throws Exception {
        ToolContextFixture f = ToolContextFixture.administrator();
        when(gate.check("{ admin { jahia } }", "root", "10.0.0.7"))
                .thenReturn(Optional.of("Operation not allowed: 'admin' is not in the whitelist"));

        ToolResult result = tool.execute(input("{ admin { jahia } }"), f.ctx);

        assertThat(result.error().code()).isEqualTo(ToolError.CODE_FORBIDDEN);
        assertThat(result.error().message()).contains("not in the whitelist");
        verify(client, never()).execute(any(), anyString(), any());
    }

    @Test
    @DisplayName("allowed query → GraphQL JSON response returned as the payload, running as the caller with its variables")
    void success_returns_graphql_json() throws Exception {
        ToolContextFixture f = ToolContextFixture.administrator();
        ObjectNode in = input("query($p:String!){ jcr { nodeByPath(path:$p) { name } } }");
        in.putObject("variables").put("p", "/");
        when(client.execute(eq(f.ctx), anyString(), any()))
                .thenReturn(new InternalGraphQLClient.Response(200, "{\"data\":{\"jcr\":{\"nodeByPath\":{\"name\":\"\"}}}}"));

        ToolResult result = tool.execute(in, f.ctx);

        assertThat(result.isOk()).isTrue();
        assertThat(result.payload().path("data").path("jcr").has("nodeByPath")).isTrue();
        verify(client).execute(eq(f.ctx), anyString(), eq(java.util.Map.of("p", "/")));
        assertThat(f.auditEvents).containsExactly("tool.execute.ok");
    }

    @Test
    @DisplayName("HTTP error from the GraphQL servlet → isError result (the old wrapper never flagged it)")
    void http_error_is_reported() throws Exception {
        ToolContextFixture f = ToolContextFixture.administrator();
        when(client.execute(any(), anyString(), any()))
                .thenReturn(new InternalGraphQLClient.Response(400, "{\"errors\":[{\"message\":\"Invalid syntax\"}]}"));

        ToolResult result = tool.execute(input("{ broken"), f.ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.error().code()).isEqualTo(ToolError.CODE_VALIDATION);
        assertThat(result.error().message()).contains("Invalid syntax");
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "HTTP {0} → {1}")
    @org.junit.jupiter.params.provider.CsvSource({"401, forbidden", "403, forbidden", "500, internal", "400, validation"})
    @DisplayName("HTTP status of the GraphQL servlet maps to a ToolError code")
    void http_status_mapping(final int status, final String code) throws Exception {
        when(client.execute(any(), anyString(), any())).thenReturn(new InternalGraphQLClient.Response(status, ""));

        ToolResult result = tool.execute(input("{ x }"), ToolContextFixture.administrator().ctx);

        assertThat(result.error().code()).isEqualTo(code);
    }

    @Test
    @DisplayName("variables that are not an object → validation error, GraphQL not called")
    void non_object_variables_rejected() throws Exception {
        ObjectNode in = input("{ x }");
        in.putArray("variables").add(1);

        ToolResult result = tool.execute(in, ToolContextFixture.administrator().ctx);

        assertThat(result.error().code()).isEqualTo(ToolError.CODE_VALIDATION);
        verify(client, never()).execute(any(), anyString(), any());
    }

    @Test
    @DisplayName("audit records a hash of the executed document (never the variables)")
    void audit_records_query_hash() throws Exception {
        ToolContextFixture f = ToolContextFixture.administrator();
        ObjectNode in = input("{ currentUser { name } }");
        in.putObject("variables").put("secret", "s3cr3t");
        when(client.execute(any(), anyString(), any())).thenReturn(new InternalGraphQLClient.Response(200, "{}"));

        tool.execute(in, f.ctx);

        assertThat(f.auditDetails.get(0)).containsKey("queryHash");
        assertThat(String.valueOf(f.auditDetails.get(0).get("queryHash"))).hasSize(64);
        assertThat(f.auditDetails.get(0).toString()).doesNotContain("s3cr3t").doesNotContain("currentUser");
    }

    @Test
    @DisplayName("a failing audit backend does not turn a completed operation into a failure")
    void audit_failure_does_not_fail_the_call() throws Exception {
        ToolContextFixture f = ToolContextFixture.administrator();
        when(f.ctx.audit()).thenReturn((event, details) -> {
            throw new IllegalStateException("audit down");
        });
        when(client.execute(any(), anyString(), any())).thenReturn(new InternalGraphQLClient.Response(200, "{\"data\":{}}"));

        ToolResult result = tool.execute(input("mutation { x }"), f.ctx);

        assertThat(result.isOk()).isTrue();
    }

    @Test
    @DisplayName("non-JSON body is still returned, as a text payload")
    void non_json_body_returned_as_text() throws Exception {
        ToolContextFixture f = ToolContextFixture.administrator();
        when(client.execute(any(), anyString(), any())).thenReturn(new InternalGraphQLClient.Response(200, "plain"));

        ToolResult result = tool.execute(input("{ x }"), f.ctx);

        assertThat(result.payload().asText()).isEqualTo("plain");
    }

    @Test
    @DisplayName("infrastructure failure → internal error without leaking the exception message")
    void infrastructure_failure() throws Exception {
        ToolContextFixture f = ToolContextFixture.administrator();
        when(client.execute(any(), anyString(), any())).thenThrow(new java.io.IOException("secret detail"));

        ToolResult result = tool.execute(input("{ x }"), f.ctx);

        assertThat(result.error().code()).isEqualTo(ToolError.CODE_INTERNAL);
        assertThat(result.error().message()).doesNotContain("secret detail");
    }
}
