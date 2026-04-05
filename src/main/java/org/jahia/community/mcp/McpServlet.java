package org.jahia.community.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import org.jahia.services.content.JCRTemplate;
import org.jahia.services.securityfilter.PermissionService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import javax.jcr.query.Query;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MCP (Model Context Protocol) servlet for Jahia.
 *
 * Registers at /modules/mcp and exposes Jahia JCR operations as MCP tools
 * so that Claude (or any MCP client) can query and navigate content.
 *
 * HttpServletStatelessServerTransport uses jakarta.servlet and has a private
 * constructor, so this servlet implements McpStatelessServerTransport directly
 * and extends javax.servlet.HttpServlet for Jahia OSGi compatibility.
 */
@Component(service = {HttpServlet.class, Servlet.class}, property = {"alias=/mcp"})
public class McpServlet extends HttpServlet implements McpStatelessServerTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpServlet.class);
    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    private final McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(new ObjectMapper());

    private McpStatelessServerHandler mcpHandler;
    private McpStatelessSyncServer mcpServer;
    private PermissionService permissionService;

    @Reference
    public void setPermissionService(PermissionService service) {
        this.permissionService = service;
    }

    @Activate
    public void activate() {
        mcpServer = McpServer.sync(this)
                .serverInfo("jahia-mcp", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(searchNodesTool(), getNodeTool(), listChildrenTool())
                .build();
        LOGGER.info("Jahia MCP server activated at /modules/mcp");
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
     * Tool: searchNodes
     * Executes a JCR SQL2 query and returns paths + primary types of matches.
     */
    private McpStatelessServerFeatures.SyncToolSpecification searchNodesTool() {
        String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "  \"query\":{\"type\":\"string\",\"description\":\"JCR SQL2 query string\"},"
                + "  \"limit\":{\"type\":\"integer\",\"description\":\"Maximum number of results (default 20, max 100)\"}"
                + "},"
                + "\"required\":[\"query\"]"
                + "}";

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("searchNodes")
                .description("Execute a JCR SQL2 query against Jahia and return matching node paths and primary types. "
                        + "Example: SELECT * FROM [jnt:page] WHERE ISDESCENDANTNODE('/sites/mySite')")
                .inputSchema(jsonMapper, schema)
                .build();

        return new McpStatelessServerFeatures.SyncToolSpecification(tool, (ctx, req) -> {
            String query = (String) req.arguments().get("query");
            int limit = req.arguments().containsKey("limit")
                    ? Math.min(((Number) req.arguments().get("limit")).intValue(), 100)
                    : 20;

            try {
                String result = JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                    var qm = session.getWorkspace().getQueryManager();
                    var q = qm.createQuery(query, Query.JCR_SQL2);
                    q.setLimit(limit);

                    StringBuilder sb = new StringBuilder("[");
                    var nodes = q.execute().getNodes();
                    while (nodes.hasNext()) {
                        var node = nodes.nextNode();
                        sb.append("{\"path\":\"").append(escapeJson(node.getPath()))
                          .append("\",\"type\":\"").append(node.getPrimaryNodeType().getName())
                          .append("\"},");
                    }
                    if (sb.length() > 1 && sb.charAt(sb.length() - 1) == ',') {
                        sb.deleteCharAt(sb.length() - 1);
                    }
                    return sb.append("]").toString();
                });
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result)), false);

            } catch (Exception e) {
                LOGGER.error("searchNodes failed for query: {}", query, e);
                return new McpSchema.CallToolResult("Error: " + e.getMessage(), true);
            }
        });
    }

    /**
     * Tool: getNode
     * Returns a node's primary type, scalar properties, and direct child node names.
     */
    private McpStatelessServerFeatures.SyncToolSpecification getNodeTool() {
        String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "  \"path\":{\"type\":\"string\",\"description\":\"Absolute JCR node path, e.g. /sites/mySite/home\"}"
                + "},"
                + "\"required\":[\"path\"]"
                + "}";

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("getNode")
                .description("Get a Jahia JCR node by absolute path, returning its type, "
                        + "scalar properties, and direct child node names.")
                .inputSchema(jsonMapper, schema)
                .build();

        return new McpStatelessServerFeatures.SyncToolSpecification(tool, (ctx, req) -> {
            String path = (String) req.arguments().get("path");

            try {
                String result = JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                    var node = session.getNode(path);
                    StringBuilder sb = new StringBuilder();
                    sb.append("{\"path\":\"").append(escapeJson(path)).append("\"")
                      .append(",\"type\":\"").append(node.getPrimaryNodeType().getName()).append("\"")
                      .append(",\"properties\":{");

                    var props = node.getProperties();
                    boolean firstProp = true;
                    while (props.hasNext()) {
                        var p = props.nextProperty();
                        if (p.getDefinition().isMultiple()) {
                            continue; // skip multi-value for brevity
                        }
                        try {
                            if (!firstProp) sb.append(",");
                            sb.append("\"").append(escapeJson(p.getName())).append("\":\"")
                              .append(escapeJson(p.getString())).append("\"");
                            firstProp = false;
                        } catch (Exception ignored) {
                            // skip unreadable properties
                        }
                    }

                    sb.append("},\"children\":[");
                    var children = node.getNodes();
                    boolean firstChild = true;
                    while (children.hasNext()) {
                        var child = children.nextNode();
                        if (!firstChild) sb.append(",");
                        sb.append("{\"name\":\"").append(escapeJson(child.getName()))
                          .append("\",\"type\":\"").append(child.getPrimaryNodeType().getName())
                          .append("\"}");
                        firstChild = false;
                    }
                    sb.append("]}");
                    return sb.toString();
                });
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result)), false);

            } catch (Exception e) {
                LOGGER.error("getNode failed for path: {}", path, e);
                return new McpSchema.CallToolResult("Error: " + e.getMessage(), true);
            }
        });
    }

    /**
     * Tool: listChildren
     * Lists direct children of a node, with optional filtering by node type.
     */
    private McpStatelessServerFeatures.SyncToolSpecification listChildrenTool() {
        String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "  \"path\":{\"type\":\"string\",\"description\":\"Absolute JCR node path\"},"
                + "  \"nodeType\":{\"type\":\"string\",\"description\":\"Filter by node type, e.g. jnt:page (optional)\"}"
                + "},"
                + "\"required\":[\"path\"]"
                + "}";

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("listChildren")
                .description("List direct child nodes of a given JCR path, "
                        + "optionally filtered by node type.")
                .inputSchema(jsonMapper, schema)
                .build();

        return new McpStatelessServerFeatures.SyncToolSpecification(tool, (ctx, req) -> {
            String path = (String) req.arguments().get("path");
            String nodeType = (String) req.arguments().getOrDefault("nodeType", null);

            try {
                String result = JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                    var node = session.getNode(path);
                    StringBuilder sb = new StringBuilder("[");
                    var children = node.getNodes();
                    boolean first = true;
                    while (children.hasNext()) {
                        var child = children.nextNode();
                        if (nodeType != null && !child.isNodeType(nodeType)) {
                            continue;
                        }
                        if (!first) sb.append(",");
                        sb.append("{\"name\":\"").append(escapeJson(child.getName()))
                          .append("\",\"path\":\"").append(escapeJson(child.getPath()))
                          .append("\",\"type\":\"").append(child.getPrimaryNodeType().getName())
                          .append("\"}");
                        first = false;
                    }
                    return sb.append("]").toString();
                });
                return new McpSchema.CallToolResult(List.of(new McpSchema.TextContent(result)), false);

            } catch (Exception e) {
                LOGGER.error("listChildren failed for path: {}", path, e);
                return new McpSchema.CallToolResult("Error: " + e.getMessage(), true);
            }
        });
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
