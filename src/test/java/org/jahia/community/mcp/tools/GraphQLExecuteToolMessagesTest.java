package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jahia.community.mcp.access.Denial;
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
                .contains("explicitly whitelisted").contains("queries are allowed by default")
                .contains("'admin'").contains("ONLY the listed operations");
        assertThat(tool.tags()).contains("reads-ugc", "destructive");
        assertThat(tool.inputSchema().toString()).contains("errors").contains("whitelisted");
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "NAMED_SPREAD|inline fragments|forbidden",
        "UNANALYSABLE|too deeply nested|forbidden",
        "MUTATION_NOT_ALLOWED|'mutation:<field>' entry|forbidden",
        "SUBSCRIPTION|Subscriptions are not supported|forbidden",
        "PROTECTED_QUERY|added to the whitelist|forbidden",
        "NEVER_ALLOWED|can never be run|forbidden",
        "NOT_WHITELISTED|added to the whitelist|forbidden",
        "PARSE|syntax error, not a refusal|validation"})
    void should_give_a_distinct_hint_per_denial_kind_whatever_the_wording(final Denial.Kind kind,
            final String hintPart, final String code) {
        final String reason = "some reworded message";
        when(gate.evaluate(anyString(), anyString(), anyString())).thenReturn(Optional.of(new Denial(kind, reason)));

        final ToolResult result = run("{ x }");

        assertThat(result.error().code()).isEqualTo(code);
        assertThat(result.error().message()).isEqualTo(reason);
        assertThat(result.error().hint()).contains(hintPart);
    }

    @Test
    void should_have_one_hint_for_every_denial_kind() {
        for (final Denial.Kind kind : Denial.Kind.values()) {
            assertThat(GraphQLExecuteTool.hintFor(kind)).as(kind.name()).isNotBlank();
        }
    }

    @Test
    void should_bound_and_sanitize_a_denial_reason() {
        when(gate.evaluate(anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(new Denial(Denial.Kind.NOT_WHITELISTED, "x\n".repeat(1000))));

        final String message = run("{ x }").error().message();

        assertThat(message).doesNotContain("\n").hasSizeLessThanOrEqualTo(ToolSupport.MAX_ECHO_CHARS + 1);
    }

    @Test
    void should_report_an_oversized_response_as_a_non_retryable_validation_error() throws Exception {
        when(gate.evaluate(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(client.execute(any(), anyString(), any())).thenThrow(new InternalGraphQLClient.ResponseTooLargeException());

        final ToolResult result = run("{ x }");

        assertThat(result.error().code()).isEqualTo("validation");
        assertThat(result.error().message()).isEqualTo("The GraphQL response exceeds the 5 MB limit.");
        assertThat(result.error().hint()).contains("narrow the query").contains("fewer fields")
                .contains("paginate").contains("Do not retry the same document");
    }

    @Test
    void should_bound_and_sanitize_graphql_errors_inside_a_successful_result() throws Exception {
        when(gate.evaluate(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        final String body = "{\"data\":null,\"errors\":[{\"message\":\"" + "a\\nb".repeat(500)
                + "\",\"path\":[\"x\"]},{\"noMessage\":1}]}";
        when(client.execute(any(), anyString(), any())).thenReturn(new InternalGraphQLClient.Response(200, body));

        final ToolResult result = run("{ x }");

        assertThat(result.isOk()).isTrue();
        final String message = result.payload().path("errors").get(0).path("message").asText();
        assertThat(message).doesNotContain("\n").hasSizeLessThanOrEqualTo(501);
        assertThat(result.payload().path("errors").get(0).path("path").get(0).asText()).isEqualTo("x");
        assertThat(result.payload().path("errors").get(1).path("noMessage").asInt()).isEqualTo(1);
    }

    @Test
    void should_describe_the_read_only_default_exposure_and_the_never_allowed_mutations() {
        assertThat(tool.description()).contains("EVERYTHING readable").contains("settings and user data")
                .contains("explicit allow list").contains("grantRoles/revokeRoles");
    }

    @ParameterizedTest(name = "HTTP {0}")
    @CsvSource({"400, Fix the GraphQL document", "401, caller lacks the permission", "403, caller lacks the permission",
        "500, Server error", "503, Server error"})
    void should_choose_the_http_hint_from_the_status(final int status, final String hintPart) throws Exception {
        when(gate.evaluate(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(client.execute(any(), anyString(), any()))
                .thenReturn(new InternalGraphQLClient.Response(status, "{\"errors\":[{\"message\":\"boom\"}]}"));

        assertThat(run("{ x }").error().hint()).contains(hintPart);
    }

    @Test
    void should_bound_and_sanitize_server_error_text() throws Exception {
        when(gate.evaluate(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        final String body = "{\"errors\":[{\"message\":\"" + "a\\nb".repeat(500) + "\"},{}]}";
        when(client.execute(any(), anyString(), any())).thenReturn(new InternalGraphQLClient.Response(400, body));

        final String message = run("{ x }").error().message();

        assertThat(message).doesNotContain("\n").hasSizeLessThan(ToolSupport.MAX_ECHO_CHARS + 60);
    }

    @Test
    void should_report_no_details_for_non_json_or_empty_errors() throws Exception {
        when(gate.evaluate(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(client.execute(any(), anyString(), any()))
                .thenReturn(new InternalGraphQLClient.Response(500, "<html>"))
                .thenReturn(new InternalGraphQLClient.Response(500, "{\"errors\":[]}"));

        assertThat(run("{ x }").error().message()).endsWith("no error details");
        assertThat(run("{ x }").error().message()).endsWith("no error details");
    }

    @Test
    void should_return_raw_text_when_a_successful_body_is_not_json() throws Exception {
        when(gate.evaluate(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(client.execute(any(), anyString(), any())).thenReturn(new InternalGraphQLClient.Response(200, "plain"));

        assertThat(run("{ x }").payload().asText()).isEqualTo("plain");
    }
}
