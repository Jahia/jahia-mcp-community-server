package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jahia.modules.mcp.spi.tool.ToolError;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.jahia.modules.mcp.spi.tool.ToolSafety;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphQLIntrospectToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STEP1 = "{\"data\":{\"__schema\":{\"queryType\":{\"name\":\"Query\"},"
            + "\"types\":[{\"name\":\"Query\",\"kind\":\"OBJECT\"},{\"name\":\"String\",\"kind\":\"SCALAR\"},"
            + "{\"name\":\"__Type\",\"kind\":\"OBJECT\"}]}}}";

    private InternalGraphQLClient client;
    private GraphQLIntrospectTool tool;

    @BeforeEach
    void setUp() {
        client = mock(InternalGraphQLClient.class);
        tool = new GraphQLIntrospectTool();
        tool.setGraphQLClient(client);
    }

    @Test
    @DisplayName("contract: namespaced name and READ_ONLY")
    void contract() {
        assertThat(tool.name()).isEqualTo("community.graphql.introspect");
        assertThat(tool.safety()).isEqualTo(ToolSafety.READ_ONLY);
    }

    @Test
    @DisplayName("fetches type list then one __type query per non-scalar, non-introspection type")
    void two_step_introspection() throws Exception {
        ToolContextFixture f = ToolContextFixture.administrator();
        when(client.execute(contains("__schema"), isNull(), any()))
                .thenReturn(new InternalGraphQLClient.Response(200, STEP1));
        when(client.execute(contains("__type(name: \"Query\")"), isNull(), any()))
                .thenReturn(new InternalGraphQLClient.Response(200, "{\"data\":{\"__type\":{\"name\":\"Query\"}}}"));

        ToolResult result = tool.execute(MAPPER.createObjectNode(), f.ctx);

        assertThat(result.payload().path("schema").path("queryType").path("name").asText()).isEqualTo("Query");
        assertThat(result.payload().path("types").has("Query")).isTrue();
        assertThat(result.payload().path("types").has("String")).isFalse();
        verify(client, never()).execute(argThat(q -> q.contains("__type(name: \"__Type\")")), any(), any());
        assertThat(f.auditEvents).containsExactly("tool.execute.ok");
    }

    @Test
    @DisplayName("types whose detail request fails are listed in skippedTypes instead of vanishing")
    void failed_types_are_reported() throws Exception {
        when(client.execute(contains("__schema"), isNull(), any()))
                .thenReturn(new InternalGraphQLClient.Response(200, STEP1));
        when(client.execute(contains("__type(name: \"Query\")"), isNull(), any()))
                .thenReturn(new InternalGraphQLClient.Response(500, "{\"errors\":[{\"message\":\"boom\"}]}"));

        ToolResult result = tool.execute(MAPPER.createObjectNode(), ToolContextFixture.administrator().ctx);

        assertThat(result.payload().path("types").has("Query")).isFalse();
        assertThat(result.payload().path("skippedTypes").toString()).contains("Query");
    }

    @Test
    @DisplayName("HTTP error on the schema step → internal error")
    void http_error_on_step1() throws Exception {
        when(client.execute(contains("__schema"), isNull(), any()))
                .thenReturn(new InternalGraphQLClient.Response(503, STEP1));
        // Every other request succeeds, so only the step-1 HTTP status can produce the error.
        when(client.execute(contains("__type(name:"), isNull(), any()))
                .thenReturn(new InternalGraphQLClient.Response(200, "{\"data\":{\"__type\":{\"name\":\"Query\"}}}"));

        ToolResult result = tool.execute(MAPPER.createObjectNode(), ToolContextFixture.administrator().ctx);

        assertThat(result.error().code()).isEqualTo(ToolError.CODE_INTERNAL);
    }

    @Test
    @DisplayName("step 1 without data → internal error")
    void empty_step1() throws Exception {
        when(client.execute(contains("__schema"), isNull(), any()))
                .thenReturn(new InternalGraphQLClient.Response(200, ""));

        ToolResult result = tool.execute(MAPPER.createObjectNode(), ToolContextFixture.administrator().ctx);

        assertThat(result.error().code()).isEqualTo(ToolError.CODE_INTERNAL);
    }

    @Test
    @DisplayName("non-administrator is refused")
    void non_admin_forbidden() {
        ToolResult result = tool.execute(MAPPER.createObjectNode(), ToolContextFixture.nonAdministrator().ctx);

        assertThat(result.error().code()).isEqualTo(ToolError.CODE_FORBIDDEN);
    }
}
