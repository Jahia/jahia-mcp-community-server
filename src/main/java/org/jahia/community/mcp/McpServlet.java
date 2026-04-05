package org.jahia.community.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.stream.Collectors;

@Component(service = {HttpServlet.class, Servlet.class},
        property = {"alias=/mcp"},
        configurationPid = "org.jahia.community.mcp")
public class McpServlet extends HttpServlet implements McpStatelessServerTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpServlet.class);
    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    @interface Config {
        String graphql_endpoint() default "http://localhost:8080/modules/graphql";
        String graphql_apiToken() default "";
    }

    private final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
    private final ObjectMapper objectMapper = new ObjectMapper();

    private McpStatelessServerHandler mcpHandler;
    private McpStatelessSyncServer mcpServer;
    private HttpClient httpClient;
    private String graphqlEndpoint;
    private String apiToken;

    @Activate
    public void activate(Config config) {
        this.graphqlEndpoint = config.graphql_endpoint();
        this.apiToken = config.graphql_apiToken();
        this.httpClient = HttpClient.newHttpClient();

        Thread currentThread = Thread.currentThread();
        ClassLoader originalCL = currentThread.getContextClassLoader();
        currentThread.setContextClassLoader(McpServlet.class.getClassLoader());
        try {
            mcpServer = McpServer.sync(this)
                    .serverInfo("jahia-mcp", "1.0.0")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .tools(executeGraphQLTool())
                    .build();
        } finally {
            currentThread.setContextClassLoader(originalCL);
        }
        LOGGER.info("Jahia MCP server activated at /modules/mcp (GraphQL endpoint: {})", graphqlEndpoint);
    }

    @Deactivate
    public void deactivate() {
        if (mcpServer != null) {
            mcpServer.close();
        }
    }

    // -------------------------------------------------------------------------
    // McpStatelessServerTransport
    // -------------------------------------------------------------------------

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        this.mcpHandler = handler;
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.empty();
    }

    // -------------------------------------------------------------------------
    // HTTP dispatch
    // -------------------------------------------------------------------------

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (mcpHandler == null) {
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MCP handler not ready");
            return;
        }

        String body = req.getReader().lines().collect(Collectors.joining());
        LOGGER.debug("MCP request: {}", body);

        try {
            McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);

            if (message instanceof McpSchema.JSONRPCRequest) {
                McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) message;
                McpSchema.JSONRPCResponse response =
                        mcpHandler.handleRequest(McpTransportContext.EMPTY, request).block();
                resp.setContentType(CONTENT_TYPE_JSON);
                resp.getWriter().write(jsonMapper.writeValueAsString(response));

            } else if (message instanceof McpSchema.JSONRPCNotification) {
                McpSchema.JSONRPCNotification notification = (McpSchema.JSONRPCNotification) message;
                mcpHandler.handleNotification(McpTransportContext.EMPTY, notification).block();
                resp.setStatus(HttpServletResponse.SC_ACCEPTED);
            }

        } catch (Exception e) {
            LOGGER.error("Error processing MCP request", e);
            resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType(CONTENT_TYPE_JSON);
        resp.getWriter().write("{\"status\":\"Jahia MCP server running\",\"version\":\"1.0.0\"}");
    }

    // -------------------------------------------------------------------------
    // Tool definitions
    // -------------------------------------------------------------------------

    /**
     * Tool: executeGraphQL
     * Executes any GraphQL operation against the Jahia graphql-dxm-provider endpoint.
     * This gives access to all operations: jcr { nodeByPath, nodeById, nodesByQuery,
     * nodesByCriteria, ... }, mutations, admin queries, and any other operation
     * registered by graphql-dxm-provider or its extensions.
     */
    private McpStatelessServerFeatures.SyncToolSpecification executeGraphQLTool() {
        String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "  \"query\":{\"type\":\"string\",\"description\":\"GraphQL query or mutation string\"},"
                + "  \"variables\":{\"type\":\"object\",\"description\":\"Optional variables map for the GraphQL operation\"}"
                + "},"
                + "\"required\":[\"query\"]"
                + "}";

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("executeGraphQL")
                .description("Execute any GraphQL operation against Jahia's graphql-dxm-provider. "
                        + "Supports all JCR queries (jcr { nodeByPath, nodeById, nodesById, nodesByPath, "
                        + "nodesByQuery, nodesByCriteria }), JCR mutations, admin queries, and all other "
                        + "operations registered by graphql-dxm-provider or its extensions.")
                .inputSchema(jsonMapper, schema)
                .build();

        return new McpStatelessServerFeatures.SyncToolSpecification(tool, (ctx, req) -> {
            String query = (String) req.arguments().get("query");
            Object variables = req.arguments().get("variables");

            try {
                Map<String, Object> body = variables != null
                        ? Map.of("query", query, "variables", variables)
                        : Map.of("query", query);
                String requestBody = objectMapper.writeValueAsString(body);

                HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                        .uri(URI.create(graphqlEndpoint))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(requestBody));

                if (apiToken != null && !apiToken.isEmpty()) {
                    requestBuilder.header("Authorization", "APIToken " + apiToken);
                }

                HttpResponse<String> response = httpClient.send(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofString());

                boolean isError = response.statusCode() >= 400;
                if (isError) {
                    LOGGER.warn("GraphQL endpoint returned HTTP {}", response.statusCode());
                }

                return McpSchema.CallToolResult.builder()
                        .addTextContent(response.body())
                        .isError(isError)
                        .build();

            } catch (Exception e) {
                LOGGER.error("executeGraphQL failed for query: {}", query, e);
                return McpSchema.CallToolResult.builder()
                        .addTextContent("Error: " + e.getMessage())
                        .isError(true)
                        .build();
            }
        });
    }
}
