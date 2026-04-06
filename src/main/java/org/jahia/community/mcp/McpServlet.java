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
import org.apache.hc.core5.http.HttpHeaders;
import org.jahia.bin.filters.jcr.JcrSessionFilter;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.securityfilter.PermissionService;
import org.jahia.services.usermanager.JahiaUser;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import reactor.core.publisher.Mono;

import javax.jcr.RepositoryException;
import javax.servlet.*;
import javax.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.stream.Collectors;

@Component(service = {HttpServlet.class, Servlet.class},
        property = {"alias=/mcp", "allow-api-token=true"})
public class McpServlet extends HttpServlet implements McpStatelessServerTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpServlet.class);
    private static final String ASYNC_NOT_SUPPORTED = "Async not supported";
    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";
    private static final String AUTH_HEADER_KEY = "authorization";
    private static final String JAHIA_USER_KEY = "jahia.user";
    private static final String MCP_ENDPOINT = "mcp";
    private static final String QUERY_ARG = "query";
    private static final String VARIABLES_ARG = "variables";
    // Dummy no-op request used as the required non-null delegate for HttpServletRequestWrapper
    private static final HttpServletRequest DUMMY_REQUEST = (HttpServletRequest) java.lang.reflect.Proxy.newProxyInstance(
            McpServlet.class.getClassLoader(),
            new Class[]{HttpServletRequest.class},
            (proxy, method, args) -> {
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    // Dummy no-op response used as the required non-null delegate for HttpServletResponseWrapper
    private static final HttpServletResponse DUMMY_RESPONSE = (HttpServletResponse) java.lang.reflect.Proxy.newProxyInstance(
            McpServlet.class.getClassLoader(),
            new Class[]{HttpServletResponse.class},
            (proxy, method, args) -> {
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                if (method.getReturnType() == long.class) return 0L;
                return null;
            });
    private final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());
    private final ObjectMapper objectMapper = new ObjectMapper();
    private McpStatelessServerHandler mcpHandler;
    private McpStatelessSyncServer mcpServer;
    private PermissionService permissionService;
    private HttpServlet gql;

    @Activate
    public void activate() {
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
        LOGGER.info("Jahia MCP community server activated at /modules/mcp (using internal GraphQL servlet)");
    }

    @Deactivate
    public void deactivate() {
        if (mcpServer != null) {
            mcpServer.close();
        }
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        this.mcpHandler = handler;
    }

    @Override
    public Mono<Void> closeGracefully() {
        return Mono.empty();
    }

    @Reference
    public void setPermissionService(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @Reference(service = HttpServlet.class, target = "(component.name=graphql.kickstart.servlet.OsgiGraphQLHttpServlet)")
    public void setGql(HttpServlet gql) {
        this.gql = gql;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (mcpHandler == null) {
            resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MCP handler not ready");
            return;
        }

        try {
            if (permissionService.hasPermission(MCP_ENDPOINT)) {

                String body = req.getReader().lines().collect(Collectors.joining());
                LOGGER.debug("MCP request: {}", body);

                String authHeader = req.getHeader(HttpHeaders.AUTHORIZATION);
                JahiaUser currentUser = JCRSessionFactory.getInstance().getCurrentUser();
                Map<String, Object> ctxMap = new java.util.HashMap<>();
                if (authHeader != null) ctxMap.put(AUTH_HEADER_KEY, authHeader);
                if (currentUser != null) ctxMap.put(JAHIA_USER_KEY, currentUser);
                McpTransportContext transportContext = ctxMap.isEmpty()
                        ? McpTransportContext.EMPTY
                        : McpTransportContext.create(ctxMap);
                try {
                    McpSchema.JSONRPCMessage message = McpSchema.deserializeJsonRpcMessage(jsonMapper, body);

                    if (message instanceof McpSchema.JSONRPCRequest) {
                        McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) message;
                        McpSchema.JSONRPCResponse response =
                                mcpHandler.handleRequest(transportContext, request).block();
                        resp.setContentType(CONTENT_TYPE_JSON);
                        resp.getWriter().write(jsonMapper.writeValueAsString(response));

                    } else if (message instanceof McpSchema.JSONRPCNotification) {
                        McpSchema.JSONRPCNotification notification = (McpSchema.JSONRPCNotification) message;
                        mcpHandler.handleNotification(transportContext, notification).block();
                        resp.setStatus(HttpServletResponse.SC_ACCEPTED);
                    }

                } catch (Exception ex) {
                    LOGGER.error("Error processing MCP request", ex);
                    resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, ex.getMessage());
                }
            }
        } catch (RepositoryException ex) {
            throw new RuntimeException(ex);
        }
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            if (permissionService.hasPermission(MCP_ENDPOINT)) {
                resp.setContentType(CONTENT_TYPE_JSON);
                resp.getWriter().write("{\"status\":\"Jahia MCP server running\",\"version\":\"1.0.0\"}");
            }
        } catch (RepositoryException e) {
            throw new RuntimeException(e);
        }
    }

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
            String query = (String) req.arguments().get(QUERY_ARG);
            Object variables = req.arguments().get(VARIABLES_ARG);

            try {
                Map<String, Object> body = variables != null
                        ? Map.of(QUERY_ARG, query, VARIABLES_ARG, variables)
                        : Map.of(QUERY_ARG, query);
                String requestBody = objectMapper.writeValueAsString(body);

                String auth = (String) ctx.get(AUTH_HEADER_KEY);
                JahiaUser user = (JahiaUser) ctx.get(JAHIA_USER_KEY);
                final HttpServletRequest requestWrapper = new McpHttpServletRequestWrapper(requestBody, auth);
                StringWriter writer = new StringWriter();
                final McpHttpServletResponseWrapper responseWrapper = new McpHttpServletResponseWrapper(DUMMY_RESPONSE, writer);
                JCRSessionFactory.getInstance().setCurrentUser(user);
                try {
                    gql.service(requestWrapper, responseWrapper);
                } finally {
                    JcrSessionFilter.endRequest();
                }

                boolean isError = responseWrapper.getStatus() >= 400;
                String result = writer.getBuffer().toString();

                if (isError) {
                    LOGGER.warn("GraphQL endpoint returned HTTP {}", responseWrapper.getStatus());
                }

                return McpSchema.CallToolResult.builder()
                        .addTextContent(result)
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

    private static class McpHttpServletRequestWrapper extends HttpServletRequestWrapper {
        private final byte[] body;
        private final String auth;

        public McpHttpServletRequestWrapper(String body, String auth) {
            super(DUMMY_REQUEST);
            this.body = body.getBytes(StandardCharsets.UTF_8);
            this.auth = auth;
        }

        @Override
        public String getMethod() {
            return "POST";
        }

        @Override
        public String getContentType() {
            return MediaType.APPLICATION_JSON_VALUE;
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public String getHeader(String name) {
            if (HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) return MediaType.APPLICATION_JSON_VALUE;
            if (HttpHeaders.AUTHORIZATION.equalsIgnoreCase(name)) return auth;
            return null;
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return auth != null
                    ? Collections.enumeration(java.util.Arrays.asList(HttpHeaders.CONTENT_TYPE, HttpHeaders.AUTHORIZATION))
                    : Collections.enumeration(Collections.singletonList(HttpHeaders.CONTENT_TYPE));
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream bais = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return bais.read();
                }

                @Override
                public boolean isFinished() {
                    return bais.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener l) {
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }

        @Override
        public boolean isAsyncSupported() {
            return false;
        }

        @Override
        public javax.servlet.AsyncContext startAsync() {
            throw new IllegalStateException(ASYNC_NOT_SUPPORTED);
        }

        @Override
        public javax.servlet.AsyncContext startAsync(javax.servlet.ServletRequest servletRequest, javax.servlet.ServletResponse servletResponse) {
            throw new IllegalStateException(ASYNC_NOT_SUPPORTED);
        }
    }

    private static class McpHttpServletResponseWrapper extends HttpServletResponseWrapper {
        private final StringWriter writer;

        public McpHttpServletResponseWrapper(HttpServletResponse resp, StringWriter writer) {
            super(resp);
            this.writer = writer;
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return new ServletOutputStream() {
                @Override
                public void write(int b) {
                    writer.write((char) b);
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(WriteListener writeListener) {
                    // ignore callback notifications
                }
            };
        }

        @Override
        public PrintWriter getWriter() {
            return new PrintWriter(writer);
        }

        @Override
        public void setContentLength(int len) {
            // ignore content length
        }
    }

}
