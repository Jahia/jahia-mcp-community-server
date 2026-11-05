package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jahia.community.mcp.access.GraphQLWhitelistGate;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GraphQL tools without the GraphQL servlet refuse with an explicit 'unavailable' error")
class MissingGraphQLServletTest {

    private final InternalGraphQLClient clientWithoutServlet = new InternalGraphQLClient(null);

    @Test
    @DisplayName("execute answers 'unavailable' naming graphql-dxm-provider, and never asks to retry blindly")
    void execute_refuses_clearly_without_the_servlet() {
        final GraphQLWhitelistGate gate = mock(GraphQLWhitelistGate.class);
        when(gate.evaluate(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        final GraphQLExecuteTool tool = new GraphQLExecuteTool(clientWithoutServlet, gate);
        final ObjectNode in = ToolSupport.MAPPER.createObjectNode().put("query", "{ currentUser { name } }");

        final ToolResult result = tool.execute(in, ToolContextFixture.administrator().ctx);

        assertThat(result.isOk()).isFalse();
        assertThat(result.error().code()).isEqualTo("unavailable");
        assertThat(result.error().message()).isEqualTo(ToolSupport.MESSAGE_GRAPHQL_UNAVAILABLE);
        assertThat(result.error().hint()).contains("graphql-dxm-provider");
    }

    @Test
    @DisplayName("introspect answers the same 'unavailable' error, with and without typeNames")
    void introspect_refuses_clearly_without_the_servlet() {
        final GraphQLIntrospectTool tool = new GraphQLIntrospectTool(clientWithoutServlet);
        final ObjectNode summary = ToolSupport.MAPPER.createObjectNode();
        final ObjectNode named = ToolSupport.MAPPER.createObjectNode();
        named.putArray("typeNames").add("Query");

        for (final ObjectNode in : new ObjectNode[] {summary, named}) {
            final ToolResult result = tool.execute(in, ToolContextFixture.administrator().ctx);
            assertThat(result.isOk()).isFalse();
            assertThat(result.error().code()).isEqualTo("unavailable");
            assertThat(result.error().message()).isEqualTo(ToolSupport.MESSAGE_GRAPHQL_UNAVAILABLE);
        }
    }

    @Test
    @DisplayName("the client signals the missing servlet with a dedicated exception type")
    void client_throws_the_dedicated_exception() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                clientWithoutServlet.execute(ToolContextFixture.administrator().ctx, "{ x }", null))
                .isInstanceOf(InternalGraphQLClient.ServletUnavailableException.class);
    }
}
