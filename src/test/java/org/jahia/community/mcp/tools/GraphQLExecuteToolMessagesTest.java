package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jahia.community.mcp.access.GraphQLWhitelistGate;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("community.graphql.execute — descriptions, denial hints, HTTP hints")
class GraphQLExecuteToolMessagesTest {

    private InternalGraphQLClient client;
    private GraphQLWhitelistGate gate;
    private GraphQLExecuteTool tool;

    @BeforeEach
    void setUp() {
        client = mock(InternalGraphQLClient.class);
        gate = mock(GraphQLWhitelistGate.class);
        tool = new GraphQLExecuteTool(client, gate);
    }

    private ToolResult run(final String query) {
        final ObjectNode in = ToolSupport.MAPPER.createObjectNode().put("query", query);
        return tool.execute(in, ToolContextFixture.administrator().ctx);
    }

    @Test
    void should_describe_errors_in_successful_results_and_whitelisted_mutations() {
        assertThat(tool.description()).contains("'errors' array").contains("successful")
                .contains("explicitly whitelisted").contains("queries are allowed by default");
        assertThat(tool.tags()).contains("reads-ugc", "destructive");
        assertThat(tool.inputSchema().toString()).contains("errors").contains("whitelisted");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "Operation not allowed: the GraphQL document could not be parsed|Fix the syntax",
        "Operation not allowed: named fragment spreads are not permitted when a whitelist is active|inline fragments",
        "Operation not allowed: 'mutation:jcr.deleteNode' is not in the whitelist|'mutation:<field>' entry",
        "Operation not allowed: mutations are refused by the read-only default|'mutation:<field>' entry",
        "Operation not allowed: 'query:jcr' is not in the whitelist|added to the whitelist"})
    void should_give_a_distinct_hint_per_denial_cause(final String reason, final String hintPart) {
        when(gate.check(anyString(), anyString(), anyString())).thenReturn(Optional.of(reason));

        final ToolResult result = run("{ x }");

        assertThat(result.error().code()).isEqualTo("forbidden");
        assertThat(result.error().message()).isEqualTo(reason);
        assertThat(result.error().hint()).contains(hintPart);
    }

    @Test
    void should_bound_and_sanitize_a_denial_reason() {
        when(gate.check(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of("x\n".repeat(1000)));

        final String message = run("{ x }").error().message();

        assertThat(message).doesNotContain("\n").hasSizeLessThanOrEqualTo(ToolSupport.MAX_ECHO_CHARS + 1);
    }

    @ParameterizedTest(name = "HTTP {0}")
    @CsvSource({"400, Fix the GraphQL document", "401, credentials were rejected", "403, credentials were rejected",
        "500, Server error", "503, Server error"})
    void should_choose_the_http_hint_from_the_status(final int status, final String hintPart) throws Exception {
        when(gate.check(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(client.execute(any(), anyString(), any()))
                .thenReturn(new InternalGraphQLClient.Response(status, "{\"errors\":[{\"message\":\"boom\"}]}"));

        assertThat(run("{ x }").error().hint()).contains(hintPart);
    }

    @Test
    void should_bound_and_sanitize_server_error_text() throws Exception {
        when(gate.check(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        final String body = "{\"errors\":[{\"message\":\"" + "a\\nb".repeat(500) + "\"},{}]}";
        when(client.execute(any(), anyString(), any())).thenReturn(new InternalGraphQLClient.Response(400, body));

        final String message = run("{ x }").error().message();

        assertThat(message).doesNotContain("\n").hasSizeLessThan(ToolSupport.MAX_ECHO_CHARS + 60);
    }

    @Test
    void should_report_no_details_for_non_json_or_empty_errors() throws Exception {
        when(gate.check(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(client.execute(any(), anyString(), any()))
                .thenReturn(new InternalGraphQLClient.Response(500, "<html>"))
                .thenReturn(new InternalGraphQLClient.Response(500, "{\"errors\":[]}"));

        assertThat(run("{ x }").error().message()).endsWith("no error details");
        assertThat(run("{ x }").error().message()).endsWith("no error details");
    }

    @Test
    void should_return_raw_text_when_a_successful_body_is_not_json() throws Exception {
        when(gate.check(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(client.execute(any(), anyString(), any())).thenReturn(new InternalGraphQLClient.Response(200, "plain"));

        assertThat(run("{ x }").payload().asText()).isEqualTo("plain");
    }
}
