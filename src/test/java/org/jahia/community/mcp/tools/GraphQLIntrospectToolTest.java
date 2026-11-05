package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jahia.modules.mcp.spi.tool.ToolError;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.jahia.modules.mcp.spi.tool.ToolSafety;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GraphQLIntrospectToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String STEP1 = "{\"data\":{\"__schema\":{\"queryType\":{\"name\":\"Query\"},"
            + "\"mutationType\":{\"name\":\"Mutation\"},"
            + "\"types\":[{\"name\":\"Query\",\"kind\":\"OBJECT\",\"description\":\"long text\"},"
            + "{\"name\":\"Mutation\",\"kind\":\"OBJECT\"},{\"name\":\"JCRNode\",\"kind\":\"INTERFACE\"},"
            + "{\"name\":\"String\",\"kind\":\"SCALAR\"},{\"name\":\"__Type\",\"kind\":\"OBJECT\"}]}}}";

    private InternalGraphQLClient client;
    private GraphQLIntrospectTool tool;

    @BeforeEach
    void setUp() {
        client = mock(InternalGraphQLClient.class);
        tool = new GraphQLIntrospectTool();
        tool.setGraphQLClient(client);
    }

    private static InternalGraphQLClient.Response typeResponse(final String name, final int descriptionChars) {
        return new InternalGraphQLClient.Response(200, "{\"data\":{\"__type\":{\"name\":\"" + name
                + "\",\"description\":\"" + "x".repeat(descriptionChars) + "\"}}}");
    }

    private void stubType(final String name, final InternalGraphQLClient.Response response) throws Exception {
        when(client.execute(contains("__type(name: $name)"), eq(Map.of("name", name)), any())).thenReturn(response);
    }

    private static ObjectNode withTypeNames(final String... names) {
        ObjectNode in = MAPPER.createObjectNode();
        ArrayNode arr = in.putArray("typeNames");
        for (String n : names) {
            arr.add(n);
        }
        return in;
    }

    @Test
    @DisplayName("contract: namespaced name, READ_ONLY, schema caps typeNames")
    void contract() {
        assertThat(tool.name()).isEqualTo("community.graphql.introspect");
        assertThat(tool.safety()).isEqualTo(ToolSafety.READ_ONLY);
        assertThat(tool.inputSchema().path("properties").path("typeNames").path("maxItems").asInt())
                .isEqualTo(GraphQLIntrospectTool.MAX_TYPE_NAMES);
    }

    @Test
    @DisplayName("no arguments → type list (name + kind) and details of the root types only")
    void default_returns_summary_and_root_types() throws Exception {
        ToolContextFixture f = ToolContextFixture.administrator();
        when(client.execute(contains("__schema"), isNull(), any())).thenReturn(new InternalGraphQLClient.Response(200, STEP1));
        stubType("Query", typeResponse("Query", 10));
        stubType("Mutation", typeResponse("Mutation", 10));

        ToolResult result = tool.execute(MAPPER.createObjectNode(), f.ctx);

        assertThat(result.payload().path("schema").path("queryType").path("name").asText()).isEqualTo("Query");
        assertThat(result.payload().path("schema").path("types").toString())
                .contains("JCRNode").doesNotContain("long text").doesNotContain("__Type");
        assertThat(result.payload().path("types").has("Query")).isTrue();
        assertThat(result.payload().path("types").has("Mutation")).isTrue();
        verify(client, never()).execute(any(), eq(Map.of("name", "JCRNode")), any());
        assertThat(f.auditEvents).containsExactly("tool.execute.ok");
    }

    @Test
    @DisplayName("typeNames → details of exactly those types, passed as GraphQL variables, no schema step")
    void type_names_fetch_requested_types() throws Exception {
        stubType("JCRNode", typeResponse("JCRNode", 10));

        ToolResult result = tool.execute(withTypeNames("JCRNode"), ToolContextFixture.administrator().ctx);

        assertThat(result.payload().path("types").has("JCRNode")).isTrue();
        assertThat(result.payload().has("schema")).isFalse();
        verify(client, never()).execute(contains("__schema"), any(), any());
        verify(client, times(1)).execute(any(), any(), any());
    }

    @Test
    @DisplayName("more than MAX_TYPE_NAMES typeNames → validation error, nothing executed")
    void too_many_type_names() throws Exception {
        String[] names = new String[GraphQLIntrospectTool.MAX_TYPE_NAMES + 1];
        for (int i = 0; i < names.length; i++) {
            names[i] = "T" + i;
        }

        ToolResult result = tool.execute(withTypeNames(names), ToolContextFixture.administrator().ctx);

        assertThat(result.error().code()).isEqualTo(ToolError.CODE_VALIDATION);
        verify(client, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("a type name that is not a GraphQL name → validation error, nothing executed")
    void invalid_type_name() throws Exception {
        ToolResult result = tool.execute(withTypeNames("Query\") { x } #"), ToolContextFixture.administrator().ctx);

        assertThat(result.error().code()).isEqualTo(ToolError.CODE_VALIDATION);
        verify(client, never()).execute(any(), any(), any());
    }

    @Test
    @DisplayName("output budget: types beyond it are listed in truncatedTypes, the first type always fits")
    void output_budget_truncates() throws Exception {
        int big = GraphQLIntrospectTool.MAX_RESULT_CHARS * 2 / 3;
        stubType("A", typeResponse("A", big));
        stubType("B", typeResponse("B", big));
        stubType("C", typeResponse("C", 10));

        ToolResult result = tool.execute(withTypeNames("A", "B", "C"), ToolContextFixture.administrator().ctx);

        assertThat(result.payload().path("types").has("A")).isTrue();
        assertThat(result.payload().path("types").has("B")).isFalse();
        assertThat(result.payload().path("truncatedTypes").toString()).contains("B").contains("C");
        verify(client, never()).execute(any(), eq(Map.of("name", "C")), any());
    }

    @Test
    @DisplayName("a type list bigger than the budget never pushes the root types out")
    void huge_type_list_keeps_root_types() throws Exception {
        StringBuilder types = new StringBuilder("{\"name\":\"Query\",\"kind\":\"OBJECT\"},{\"name\":\"Mutation\",\"kind\":\"OBJECT\"}");
        for (int i = 0; types.length() < GraphQLIntrospectTool.MAX_RESULT_CHARS * 2; i++) {
            types.append(",{\"name\":\"Type").append(i).append("\",\"kind\":\"OBJECT\"}");
        }
        String step1 = "{\"data\":{\"__schema\":{\"queryType\":{\"name\":\"Query\"},"
                + "\"mutationType\":{\"name\":\"Mutation\"},\"types\":[" + types + "]}}}";
        when(client.execute(contains("__schema"), isNull(), any())).thenReturn(new InternalGraphQLClient.Response(200, step1));
        stubType("Query", typeResponse("Query", 10));
        stubType("Mutation", typeResponse("Mutation", 10));

        ToolResult result = tool.execute(MAPPER.createObjectNode(), ToolContextFixture.administrator().ctx);

        assertThat(result.payload().path("types").has("Query")).isTrue();
        assertThat(result.payload().path("types").has("Mutation")).isTrue();
        assertThat(result.payload().path("truncatedTypes")).isEmpty();
    }

    @Test
    @DisplayName("duplicate typeNames are fetched once")
    void duplicate_type_names_fetched_once() throws Exception {
        stubType("JCRNode", typeResponse("JCRNode", 10));

        tool.execute(withTypeNames("JCRNode", "JCRNode", "JCRNode"), ToolContextFixture.administrator().ctx);

        verify(client, times(1)).execute(any(), eq(Map.of("name", "JCRNode")), any());
    }

    @Test
    @DisplayName("unknown or failing types are listed in skippedTypes instead of vanishing")
    void failed_types_are_reported() throws Exception {
        stubType("Nope", new InternalGraphQLClient.Response(200, "{\"data\":{\"__type\":null}}"));
        stubType("Boom", new InternalGraphQLClient.Response(500, "{\"errors\":[{\"message\":\"boom\"}]}"));

        ToolResult result = tool.execute(withTypeNames("Nope", "Boom"), ToolContextFixture.administrator().ctx);

        assertThat(result.payload().path("skippedTypes").toString()).contains("Nope").contains("Boom");
    }

    @Test
    @DisplayName("HTTP error on the schema step → internal error")
    void http_error_on_step1() throws Exception {
        when(client.execute(contains("__schema"), isNull(), any())).thenReturn(new InternalGraphQLClient.Response(503, STEP1));
        stubType("Query", typeResponse("Query", 10));
        stubType("Mutation", typeResponse("Mutation", 10));

        ToolResult result = tool.execute(MAPPER.createObjectNode(), ToolContextFixture.administrator().ctx);

        assertThat(result.error().code()).isEqualTo(ToolError.CODE_INTERNAL);
    }

    @Test
    @DisplayName("schema step without data → internal error")
    void empty_step1() throws Exception {
        when(client.execute(contains("__schema"), isNull(), any())).thenReturn(new InternalGraphQLClient.Response(200, ""));

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
