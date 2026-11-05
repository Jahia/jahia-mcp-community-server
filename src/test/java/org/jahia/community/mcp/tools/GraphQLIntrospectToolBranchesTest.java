package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("community.graphql.introspect — validation, failure and budget branches")
class GraphQLIntrospectToolBranchesTest {

    private InternalGraphQLClient client;
    private GraphQLIntrospectTool tool;

    @BeforeEach
    void setUp() {
        client = mock(InternalGraphQLClient.class);
        tool = new GraphQLIntrospectTool(client);
    }

    private ToolResult run(final JsonNode typeNames) {
        final ObjectNode in = ToolSupport.MAPPER.createObjectNode();
        if (typeNames != null) {
            in.set("typeNames", typeNames);
        }
        return tool.execute(in, ToolContextFixture.administrator().ctx);
    }

    private static ArrayNode names(final String... n) {
        final ArrayNode a = ToolSupport.MAPPER.createArrayNode();
        for (final String s : n) {
            a.add(s);
        }
        return a;
    }

    @Test
    void should_mention_that_the_whitelist_may_refuse_listed_operations() {
        assertThat(tool.description()).contains("whitelist may refuse").contains("explicitly whitelisted");
        assertThat(tool.inputSchema().toString()).contains("whitelist may still refuse");
        assertThat(tool.tags()).containsExactly("community");
    }

    @Test
    void should_reject_a_non_array_empty_or_oversized_typeNames() {
        assertThat(run(ToolSupport.MAPPER.getNodeFactory().textNode("Query")).error().code()).isEqualTo("validation");
        assertThat(run(names()).error().code()).isEqualTo("validation");
        final String[] many = new String[GraphQLIntrospectTool.MAX_TYPE_NAMES + 1];
        java.util.Arrays.fill(many, "T");
        assertThat(run(names(many)).error().message()).contains("1 to 25");
    }

    @Test
    void should_reject_non_name_entries() {
        assertThat(run(names("bad name")).error().message()).contains("GraphQL type names");
        final ArrayNode numeric = ToolSupport.MAPPER.createArrayNode().add(1);
        assertThat(run(numeric).error().code()).isEqualTo("validation");
    }

    @Test
    void should_treat_a_null_typeNames_as_absent() throws Exception {
        when(client.execute(any(), contains("__schema"), any()))
                .thenReturn(new InternalGraphQLClient.Response(200, "{\"data\":{\"__schema\":{}}}"));

        final ToolResult result = run(ToolSupport.MAPPER.nullNode());

        assertThat(result.isOk()).isTrue();
        assertThat(result.payload().has("schema")).isTrue();
    }

    @Test
    void should_return_internal_error_when_the_client_throws() throws Exception {
        when(client.execute(any(), anyString(), any())).thenThrow(new IOException("down"));

        assertThat(run(null).error().code()).isEqualTo("internal");
        assertThat(run(names("Query")).error().code()).isEqualTo("internal");
    }

    @Test
    void should_skip_unknown_types_and_dedupe_names() throws Exception {
        when(client.execute(any(), contains("__type"), any()))
                .thenReturn(new InternalGraphQLClient.Response(200, "{\"data\":{\"__type\":null}}"));

        final ToolResult result = run(names("Nope", "Nope"));

        assertThat(result.payload().path("skippedTypes")).hasSize(1);
        assertThat(result.payload().path("types").isEmpty()).isTrue();
    }

    @Test
    void should_truncate_when_a_later_type_would_exceed_the_budget_and_keep_the_first() throws Exception {
        final String big = "x".repeat(GraphQLIntrospectTool.MAX_RESULT_CHARS - 100);
        when(client.execute(any(), contains("__type"), any())).thenReturn(
                new InternalGraphQLClient.Response(200,
                        "{\"data\":{\"__type\":{\"name\":\"A\",\"d\":\"" + big + "\"}}}"));

        final ToolResult result = run(names("A", "B", "C"));

        assertThat(result.payload().path("types").has("A")).isTrue();
        assertThat(result.payload().path("truncatedTypes")).hasSize(2);
    }
}
