package org.jahia.community.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentSpread;
import graphql.language.InlineFragment;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.parser.Parser;
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
import org.jahia.community.mcp.config.McpConfigService;
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
import java.util.*;

// S2226: OSGi-injected fields are written on bind/activate threads and read on request
// threads — volatile provides the required visibility without a lock.
@SuppressWarnings({"java:S2226","java:S3077"})
@Component(service = {HttpServlet.class, Servlet.class},
        property = {"alias=/community-mcp", "allow-api-token=true"})
public class McpServlet extends HttpServlet implements McpStatelessServerTransport {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpServlet.class);
    private static final String ASYNC_NOT_SUPPORTED = "Async not supported";
    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";
    private static final String AUTH_HEADER_KEY = "authorization";
    private static final String JAHIA_USER_KEY = "jahia.user";
    private static final String CLIENT_IP_KEY = "client.ip";
    private static final String MCP_ENDPOINT = "community-mcp";
    private static final String QUERY_ARG = "query";
    private static final String VARIABLES_ARG = "variables";
    private static final McpJsonMapper JSON_MAPPER = new JacksonMcpJsonMapper(new ObjectMapper());
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String FAILED_TO_SEND_ERROR_RESPONSE = "Failed to send error response";
    private static final String FAILED_TO_WRITE_RESPONSE = "Failed to write response";
    private static final String REPOSITORY_ERROR_DURING_MCP_REQUEST = "Repository error during MCP request";
    private static final String INTERNAL_ERROR_MSG = "Internal error executing operation";
    // S1192: JSON-RPC error envelope fragments used in every error response
    private static final String JSONRPC_ERROR_PREFIX = "{\"errors\":[{\"message\":\""; // S1192
    private static final String JSONRPC_ERROR_SUFFIX = "\"}]}";
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
    // volatile: written on OSGi bind/activate threads, read on servlet request threads
    private volatile McpStatelessServerHandler mcpHandler;
    private volatile McpStatelessSyncServer mcpServer;
    private volatile PermissionService permissionService;
    private volatile HttpServlet gql;
    private volatile McpConfigService mcpConfigService;
    private volatile McpSkillService mcpSkillService;

    @Activate
    public void activate() {
        final Thread currentThread = Thread.currentThread();
        final ClassLoader originalCL = currentThread.getContextClassLoader();
        currentThread.setContextClassLoader(McpServlet.class.getClassLoader());
        try {
            mcpServer = McpServer.sync(this)
                    .serverInfo("jahia-mcp", "1.0.0")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                    .tools(executeGraphQLTool(), introspectSchemaTool(), listSkillsTool(), getSkillTool())
                    .build();
        } finally {
            currentThread.setContextClassLoader(originalCL);
        }
        LOGGER.info("Jahia MCP community server activated at /modules/community-mcp (using internal GraphQL servlet)");
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

    @Reference
    public void setMcpConfigService(McpConfigService mcpConfigService) {
        this.mcpConfigService = mcpConfigService;
    }

    @Reference
    public void setMcpSkillService(McpSkillService mcpSkillService) {
        this.mcpSkillService = mcpSkillService;
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        if (mcpHandler == null) {
            try {
                resp.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, "MCP handler not ready");
            } catch (IOException ex) {
                LOGGER.error(FAILED_TO_SEND_ERROR_RESPONSE, ex);
            }
            return;
        }

        try {
            if (permissionService.hasPermission(MCP_ENDPOINT)) {
                handleAuthorizedRequest(req, resp);
            } else {
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.setHeader("WWW-Authenticate", "APIToken realm=\"community-mcp\"");
            }
        } catch (IOException | RepositoryException ex) {
            LOGGER.error("Error processing MCP request", ex);
            try {
                resp.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, INTERNAL_ERROR_MSG);
            } catch (IOException ioEx) {
                LOGGER.error(FAILED_TO_SEND_ERROR_RESPONSE, ioEx);
            }
        }
    }

    /** Maximum POST body size (2 MB) accepted before rejection. */
    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;

    private void handleAuthorizedRequest(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // SEC-5: reject oversized bodies before any parsing
        final int contentLength = req.getContentLength();
        if (contentLength > MAX_BODY_BYTES) {
            LOGGER.warn("MCP request body too large: Content-Length={}", contentLength);
            sendJsonRpcError(resp, null, -32700, "Request body too large");
            return;
        }
        final String body = readBodyCapped(req);
        if (body == null) {
            sendJsonRpcError(resp, null, -32700, "Request body too large");
            return;
        }
        LOGGER.debug("MCP request: {}", body);
        final String authHeader = req.getHeader(HttpHeaders.AUTHORIZATION);
        final JahiaUser currentUser = JCRSessionFactory.getInstance().getCurrentUser();
        final Map<String, Object> ctxMap = new java.util.HashMap<>();
        if (authHeader != null) ctxMap.put(AUTH_HEADER_KEY, authHeader);
        if (currentUser != null) ctxMap.put(JAHIA_USER_KEY, currentUser);
        ctxMap.put(CLIENT_IP_KEY, getClientIp(req));
        final McpTransportContext transportContext = ctxMap.isEmpty()
                ? McpTransportContext.EMPTY
                : McpTransportContext.create(ctxMap);
        // ERR-1: JSON parse failure → -32700
        final McpSchema.JSONRPCMessage message;
        try {
            message = McpSchema.deserializeJsonRpcMessage(JSON_MAPPER, body);
        } catch (Exception ex) {
            LOGGER.warn("MCP JSON-RPC parse error: {}", ex.getMessage());
            sendJsonRpcError(resp, null, -32700, "Parse error");
            return;
        }
        if (message instanceof McpSchema.JSONRPCRequest) {
            final McpSchema.JSONRPCRequest request = (McpSchema.JSONRPCRequest) message;
            final McpSchema.JSONRPCResponse response =
                    mcpHandler.handleRequest(transportContext, request).block();
            resp.setContentType(CONTENT_TYPE_JSON);
            resp.getWriter().write(JSON_MAPPER.writeValueAsString(response));
        } else if (message instanceof McpSchema.JSONRPCNotification) {
            final McpSchema.JSONRPCNotification notification = (McpSchema.JSONRPCNotification) message;
            mcpHandler.handleNotification(transportContext, notification).block();
            resp.setStatus(HttpServletResponse.SC_ACCEPTED);
        } else {
            // ERR-4: neither Request nor Notification
            LOGGER.warn("MCP received invalid JSON-RPC message type");
            sendJsonRpcError(resp, null, -32600, "Invalid Request");
        }
    }

    /** Reads the request body up to MAX_BODY_BYTES; returns null if cap exceeded. */
    private static String readBodyCapped(HttpServletRequest req) throws IOException {
        final char[] buf = new char[4096];
        final StringBuilder sb = new StringBuilder();
        try (java.io.Reader reader = req.getReader()) {
            int n;
            while ((n = reader.read(buf)) != -1) {
                sb.append(buf, 0, n);
                if (sb.length() > MAX_BODY_BYTES) {
                    LOGGER.warn("MCP request body exceeded {} bytes during read", MAX_BODY_BYTES);
                    return null;
                }
            }
        }
        return sb.toString();
    }

    /** Writes a minimal JSON-RPC 2.0 error response. id may be null. */
    private static void sendJsonRpcError(HttpServletResponse resp, Object id, int code, String message) throws IOException {
        resp.setContentType(CONTENT_TYPE_JSON);
        final String idJson = id == null ? "null" : "\"" + id + "\"";
        resp.getWriter().write(
                "{\"jsonrpc\":\"2.0\",\"id\":"  + idJson
                + ",\"error\":{\"code\":" + code
                + ",\"message\":\"" + message.replace("\"", "\\\"") + "\"}}"
        );
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        try {
            if (permissionService.hasPermission(MCP_ENDPOINT)) {
                resp.setContentType(CONTENT_TYPE_JSON);
                resp.getWriter().write("{\"status\":\"Jahia MCP server running\",\"version\":\"1.0.0\",\"tools\":[\"executeGraphQL\",\"introspectSchema\",\"listSkills\",\"getSkill\"]}");
            } else {
                // ERR-9: return 401 + WWW-Authenticate for unauthenticated GET
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.setHeader("WWW-Authenticate", "APIToken realm=\"community-mcp\"");
            }
        } catch (IOException ex) {
            LOGGER.error(FAILED_TO_WRITE_RESPONSE, ex);
        } catch (RepositoryException ex) {
            LOGGER.error(REPOSITORY_ERROR_DURING_MCP_REQUEST, ex);
        }
    }

    /**
     * Tool: executeGraphQL
     * Executes any GraphQL operation against the Jahia graphql-dxm-provider endpoint,
     * subject to the configured whitelist access control.
     */
    private McpStatelessServerFeatures.SyncToolSpecification executeGraphQLTool() {
        final String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "  \"query\":{\"type\":\"string\",\"description\":\"GraphQL query or mutation string\"},"
                + "  \"variables\":{\"type\":\"object\",\"description\":\"Optional variables map for the GraphQL operation\"}"
                + "},"
                + "\"required\":[\"query\"]"
                + "}";

        final McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("executeGraphQL")
                .description("Execute any GraphQL query or mutation against Jahia's graphql-dxm-provider. "
                        + "Call introspectSchema first to discover all available operations and their arguments.")
                .inputSchema(JSON_MAPPER, schema)
                .build();

        return new McpStatelessServerFeatures.SyncToolSpecification(tool, (ctx, req) -> {
            // CORR-1: validate query present before any use
            final Object rawQuery = req.arguments().get(QUERY_ARG);
            if (!(rawQuery instanceof String) || ((String) rawQuery).isBlank()) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent(JSONRPC_ERROR_PREFIX + "'query' argument is required and must not be blank" + JSONRPC_ERROR_SUFFIX)
                        .isError(true)
                        .build();
            }
            final String query = (String) rawQuery;
            final Object variables = req.arguments().get(VARIABLES_ARG);

            // Access control: check whitelist/blacklist before forwarding to GraphQL engine
            final McpSchema.CallToolResult blocked = checkAccess(
                    query,
                    (JahiaUser) ctx.get(JAHIA_USER_KEY),
                    (String) ctx.get(CLIENT_IP_KEY));
            if (blocked != null) {
                return blocked;
            }

            try {
                final Map<String, Object> body = variables != null
                        ? Map.of(QUERY_ARG, query, VARIABLES_ARG, variables)
                        : Map.of(QUERY_ARG, query);
                final String requestBody = OBJECT_MAPPER.writeValueAsString(body);

                final String auth = (String) ctx.get(AUTH_HEADER_KEY);
                final JahiaUser user = (JahiaUser) ctx.get(JAHIA_USER_KEY);
                final HttpServletRequest requestWrapper = new McpHttpServletRequestWrapper(requestBody, auth);
                final StringWriter writer = new StringWriter();
                final McpHttpServletResponseWrapper responseWrapper = new McpHttpServletResponseWrapper(DUMMY_RESPONSE, writer);
                JCRSessionFactory.getInstance().setCurrentUser(user);
                try {
                    gql.service(requestWrapper, responseWrapper);
                } finally {
                    JcrSessionFilter.endRequest();
                }

                final boolean isError = responseWrapper.getStatus() >= 400;
                final String result = writer.getBuffer().toString();

                if (isError) {
                    LOGGER.warn("GraphQL endpoint returned HTTP {}", responseWrapper.getStatus());
                }

                return McpSchema.CallToolResult.builder()
                        .addTextContent(result)
                        .isError(isError)
                        .build();

            } catch (Exception ex) {
                LOGGER.error("executeGraphQL failed", ex);
                return McpSchema.CallToolResult.builder()
                        .addTextContent(JSONRPC_ERROR_PREFIX + INTERNAL_ERROR_MSG + JSONRPC_ERROR_SUFFIX)
                        .isError(true)
                        .build();
            }
        });
    }

    /**
     * Returns the TCP peer address of the client.
     *
     * <p>X-Forwarded-For is intentionally not trusted here: without a known
     * trusted-proxy list, the header is trivially spoofable. If you deploy
     * Jahia behind a reverse proxy and need real-client IP, configure
     * RemoteIpValve at the Tomcat layer instead of reading the header here.
     */
    private static String getClientIp(final HttpServletRequest req) {
        return req.getRemoteAddr();
    }

    /**
     * Returns a blocked-result if the query violates the whitelist, null otherwise.
     * Entries are dot-separated path prefixes: "admin" covers all sub-operations of admin,
     * "admin.jahia.shutdown" covers only that specific nested path.
     * Introspection fields (__schema, __type, __typename) always pass.
     */
    // Package-private (not private) so security unit tests can drive the REAL access-control
    // decision with a mocked McpConfigService, instead of asserting against a re-implemented
    // mirror. See McpServletCheckAccessTest.
    McpSchema.CallToolResult checkAccess(final String query, final JahiaUser user, final String clientIp) {
        final Set<String> whitelist = mcpConfigService.getWhitelist();

        if (whitelist.isEmpty()) {
            // SECURITY POSTURE (intentional current default — do NOT change the meaning here
            // without product/maintainer sign-off): an EMPTY whitelist means ALLOW-ALL. The
            // shipped org.jahia.community.mcp.cfg intentionally ships whitelist= empty so the
            // module is usable out of the box without an operator first authoring a whitelist.
            //
            // This is a deliberate "secure once an operator opts in" tradeoff, NOT an oversight.
            // In allow-all mode any caller holding a valid community-mcp-scoped token can run any
            // GraphQL op via executeGraphQL. The real backstops that make this acceptable as
            // shipped are:
            //   (a) the community-mcp API permission is granted only to the admin role
            //       (org.jahia.bundles.api.authorization-community-mcp.yml → user_permission: admin),
            //       so a caller already holds broad privileges; and
            //   (b) the forwarded caller identity + JCR ACLs still apply to every executeGraphQL
            //       call (see executeGraphQL: setCurrentUser(user) + forwarded Authorization),
            //       so operations remain bounded by what that user could do in Jahia anyway.
            //
            // KNOWN HARDENING RECOMMENDATION (deferred — flipping empty→deny-all is a BREAKING
            // change for deployments relying on this permissive default, so it needs product
            // buy-in and a major version): consider a fail-closed default, or shipping a sensible
            // default whitelist, in a future major release. Operators wanting least-privilege
            // today should configure an explicit whitelist (Administration -> MCP Server).
            //
            // We warn loudly on EVERY call in this mode so the posture is visible in logs.
            LOGGER.warn("MCP GraphQL gate is running in ALLOW-ALL mode (whitelist is empty). "
                    + "All GraphQL operations are permitted for any community-mcp-scoped token. "
                    + "This is the intentional out-of-box default; for least-privilege configure a "
                    + "whitelist in Administration -> MCP Server to restrict access.");
            return null;
        }

        // SEC-364: parse ONCE, with the real GraphQL grammar. A document the gate cannot parse
        // is DENIED. The previous hand-written scanner returned an empty path set for anything it
        // could not walk, and the branch below read empty as "nothing to police" — so a single
        // leading comma (an ignored token in the grammar) executed the operation it had refused.
        final Document document;
        try {
            document = Parser.parse(query);
        } catch (RuntimeException ex) {
            // InvalidSyntaxException, plus any ParserOptions guard trip (max tokens / characters /
            // rule depth). Every one of them means "I cannot account for this document" → deny.
            return buildUnparseableBlockedResult(user, clientIp, ex);
        }

        // SEC-2: named fragment spreads cannot be resolved against the whitelist without
        // the full fragment definitions — fail closed rather than allowing a bypass.
        if (containsNamedFragmentSpread(document)) {
            return buildNamedFragmentBlockedResult(user, clientIp);
        }

        int maxDepth = 1;
        for (final String e : whitelist) maxDepth = Math.max(maxDepth, segmentCount(e));

        final Set<String> paths = collectNonIntrospectionPaths(document, maxDepth);
        if (paths.isEmpty()) {
            // Safe to permit: the document PARSED, so an empty set can only mean it selects no
            // non-introspection fields (collectNonIntrospectionPaths strips __-prefixed segments
            // by design). The "extractor gave up" case no longer reaches here — it denies above.
            return null;
        }

        return findFirstBlockedPath(paths, whitelist, user, clientIp);
    }

    /** Audit-log identity for a caller: the user name, or "anonymous" when there is no user. */
    private static String userNameOf(final JahiaUser user) {
        return user != null ? user.getName() : "anonymous";
    }

    /** Logs and returns a blocked result when a named fragment spread is detected. */
    private McpSchema.CallToolResult buildNamedFragmentBlockedResult(final JahiaUser user, final String clientIp) {
        LOGGER.warn("MCP operation blocked: named fragment spreads not permitted when "
                + "whitelist is active, user='{}', ip='{}'",
                userNameOf(user), clientIp);
        return McpSchema.CallToolResult.builder()
                .addTextContent(JSONRPC_ERROR_PREFIX + "Operation not allowed: "
                        + "named fragment spreads are not permitted when a whitelist is active" + JSONRPC_ERROR_SUFFIX)
                .isError(true)
                .build();
    }

    /** Logs and returns a blocked result when the submitted document cannot be parsed. */
    private McpSchema.CallToolResult buildUnparseableBlockedResult(
            final JahiaUser user, final String clientIp, final RuntimeException ex) {
        // The parser message can quote document content back — keep it in the audit log only.
        LOGGER.warn("MCP operation blocked: GraphQL document could not be parsed while a whitelist "
                + "is active, user='{}', ip='{}', reason='{}'",
                userNameOf(user), clientIp, ex.getMessage());
        return McpSchema.CallToolResult.builder()
                .addTextContent(JSONRPC_ERROR_PREFIX + "Operation not allowed: "
                        + "the GraphQL document could not be parsed" + JSONRPC_ERROR_SUFFIX)
                .isError(true)
                .build();
    }

    /**
     * Extracts field paths from the query and removes introspection segments.
     * Package-private so the {@code __}-filter regression guard tests exercise this real
     * production method rather than a test-local copy of the filtering logic.
     */
    static Set<String> collectNonIntrospectionPaths(final String query, final int maxDepth) {
        return withoutIntrospectionPaths(extractFieldPaths(query, maxDepth));
    }

    /** AST form of {@link #collectNonIntrospectionPaths(String, int)}. */
    static Set<String> collectNonIntrospectionPaths(final Document doc, final int maxDepth) {
        return withoutIntrospectionPaths(extractFieldPaths(doc, maxDepth));
    }

    private static Set<String> withoutIntrospectionPaths(final Set<String> extracted) {
        final Set<String> paths = new LinkedHashSet<>(extracted);
        paths.removeIf(p -> {
            for (final String seg : p.split("\\.", -1)) {
                if (seg.startsWith("__")) return true;
            }
            return false;
        });
        return paths;
    }

    /** Returns a blocked result for the first path not allowed by the whitelist, or null if all pass. */
    private static McpSchema.CallToolResult findFirstBlockedPath(
            final Set<String> paths, final Set<String> whitelist,
            final JahiaUser user, final String clientIp) {
        for (final String path : paths) {
            if (!isPathAllowed(path, whitelist)) {
                LOGGER.warn("MCP operation blocked: path='{}', reason=not in whitelist, user='{}', ip='{}'",
                        path, userNameOf(user), clientIp);
                return McpSchema.CallToolResult.builder()
                        .addTextContent(JSONRPC_ERROR_PREFIX + "Operation not allowed: '"
                                + path + "' is not in the whitelist" + JSONRPC_ERROR_SUFFIX)
                        .isError(true)
                        .build();
            }
        }
        return null;
    }

    // Package-private so the case-sensitivity / dot-path coverage regression guards test the
    // REAL matching logic (the previous test mirrored this method, defeating the guard).
    static boolean isPathAllowed(final String path, final Set<String> whitelist) {
        for (final String entry : whitelist) {
            if (pathCoveredBy(path, entry) || pathIsContainerOf(path, entry)) {
                return true;
            }
        }
        return false;
    }

    /** True if entry is a dot-segment prefix of (or equal to) path. */
    private static boolean pathCoveredBy(final String path, final String entry) {
        return path.equals(entry) || path.startsWith(entry + ".");
    }

    /** True if path is an intermediate container leading toward a more-specific entry. */
    private static boolean pathIsContainerOf(final String path, final String entry) {
        return entry.startsWith(path + ".");
    }

    private static int segmentCount(final String entry) {
        int n = 1;
        for (int k = 0; k < entry.length(); k++) {
            if (entry.charAt(k) == '.') n++;
        }
        return n;
    }

    /**
     * Returns {@code true} if the query contains a named fragment spread
     * ({@code ...FragmentName}). Inline fragments ({@code ... on TypeName { ... }}) are allowed.
     *
     * <p>Package-private so the regression guards exercise the REAL production method. This
     * String overload parses the document; {@link #checkAccess} uses the {@link Document}
     * overload so a request is parsed exactly once.
     *
     * @throws graphql.parser.InvalidSyntaxException if the document cannot be parsed
     */
    static boolean containsNamedFragmentSpread(final String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return containsNamedFragmentSpread(Parser.parse(query));
    }

    /** AST form of {@link #containsNamedFragmentSpread(String)}. */
    static boolean containsNamedFragmentSpread(final Document doc) {
        for (final Definition<?> def : doc.getDefinitions()) {
            if (def instanceof OperationDefinition
                    && hasNamedSpread(((OperationDefinition) def).getSelectionSet())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNamedSpread(final SelectionSet selectionSet) {
        if (selectionSet == null) {
            return false;
        }
        for (final Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof FragmentSpread) {
                return true;
            }
            if (selection instanceof Field
                    && hasNamedSpread(((Field) selection).getSelectionSet())) {
                return true;
            }
            if (selection instanceof InlineFragment
                    && hasNamedSpread(((InlineFragment) selection).getSelectionSet())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts all field paths up to {@code maxDepth} from a GraphQL document.
     * Segments are joined with dots: a depth-3 path looks like "admin.jahia.shutdown".
     * Aliases resolve to the real field name, inline fragments contribute their selections to the
     * enclosing prefix, and EVERY operation definition in the document is walked.
     *
     * <p>SEC-364: this used to be a hand-written scanner over the query string. Anything it could
     * not walk yielded an empty set, which {@link #checkAccess} could not distinguish from "this
     * document requests nothing I need to police" — so a document as ordinary as {@code query,{...}}
     * (a comma is an ignored token in the GraphQL grammar) escaped the whitelist entirely. Parsing
     * with the real grammar makes "cannot parse" an exception the caller must handle instead.
     *
     * @throws graphql.parser.InvalidSyntaxException if the document cannot be parsed
     */
    static Set<String> extractFieldPaths(final String query, final int maxDepth) {
        if (query == null || query.isBlank()) {
            return Collections.emptySet();
        }
        return extractFieldPaths(Parser.parse(query), maxDepth);
    }

    /** AST form of {@link #extractFieldPaths(String, int)}. */
    static Set<String> extractFieldPaths(final Document doc, final int maxDepth) {
        final Set<String> paths = new LinkedHashSet<>();
        for (final Definition<?> def : doc.getDefinitions()) {
            if (def instanceof OperationDefinition) {
                collectPaths(((OperationDefinition) def).getSelectionSet(), "", 0, maxDepth, paths);
            }
        }
        return paths;
    }

    private static void collectPaths(final SelectionSet selectionSet, final String prefix,
            final int depth, final int maxDepth, final Set<String> paths) {
        if (selectionSet == null) {
            return;
        }
        for (final Selection<?> selection : selectionSet.getSelections()) {
            if (selection instanceof Field) {
                collectFieldPath((Field) selection, prefix, depth, maxDepth, paths);
            } else if (selection instanceof InlineFragment) {
                // An inline fragment is a type condition, not a field: its selections belong to the
                // SAME path prefix and depth as the fragment's parent.
                collectPaths(((InlineFragment) selection).getSelectionSet(), prefix, depth, maxDepth, paths);
            }
            // FragmentSpread is deliberately NOT expanded — checkAccess denies any document
            // containing one before extraction runs (see containsNamedFragmentSpread).
        }
    }

    private static void collectFieldPath(final Field field, final String prefix,
            final int depth, final int maxDepth, final Set<String> paths) {
        // getName() is the real field name; an alias label never reaches the whitelist check.
        final String path = prefix.isEmpty() ? field.getName() : prefix + "." + field.getName();
        paths.add(path);
        // Stop at maxDepth: a deeper path can only be covered by the same whitelist entry that
        // already covers this one (see pathCoveredBy), so descending further decides nothing.
        if (depth + 1 < maxDepth) {
            collectPaths(field.getSelectionSet(), path, depth + 1, maxDepth, paths);
        }
    }

    // Step 1: enumerate all type names + root type names — zero __Type.fields selections → always safe.
    private static final String INTROSPECTION_STEP1 = "{"
            + "  __schema {"
            + "    queryType { name }"
            + "    mutationType { name }"
            + "    subscriptionType { name }"
            + "    types { name kind description }"
            + "  }"
            + "}";

    // Step 2 template: one request per type — a single __Type.fields selection, always safe.
    private static final String INTROSPECTION_TYPE_QUERY =
            "{ __type(name: \"%s\") {"
            + "  name kind description"
            + "  fields(includeDeprecated: false) {"
            + "    name description"
            + "    args { name description type { name kind ofType { name kind ofType { name kind } } } }"
            + "    type { name kind ofType { name kind ofType { name kind } } }"
            + "  }"
            + "  inputFields { name description type { name kind ofType { name kind ofType { name kind } } } }"
            + "  enumValues(includeDeprecated: false) { name description }"
            + "  interfaces { name kind }"
            + "  possibleTypes { name kind }"
            + "} }";

    /**
     * Tool: introspectSchema
     * Builds a complete schema picture through multiple safe requests, each with
     * only one __Type.fields selection, bypassing Jahia's bad-faith introspection
     * guard which fires when __Type.fields appears too many times in a single query.
     */
    private McpStatelessServerFeatures.SyncToolSpecification introspectSchemaTool() {
        final McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("introspectSchema")
                .description("Returns all available top-level GraphQL query and mutation operations "
                        + "exposed by Jahia's graphql-dxm-provider and its installed extensions, "
                        + "including full type details for all named types. "
                        + "Call this first to discover what operations and arguments are available "
                        + "before calling executeGraphQL.")
                .inputSchema(JSON_MAPPER, "{\"type\":\"object\",\"properties\":{}}")
                .build();

        return new McpStatelessServerFeatures.SyncToolSpecification(tool, (ctx, req) -> {
            try {
                final String auth = (String) ctx.get(AUTH_HEADER_KEY);
                final JahiaUser user = (JahiaUser) ctx.get(JAHIA_USER_KEY);
                return buildIntrospectionResult(auth, user);
            } catch (Exception ex) {
                LOGGER.error("introspectSchema failed", ex);
                return McpSchema.CallToolResult.builder()
                        .addTextContent(JSONRPC_ERROR_PREFIX + INTERNAL_ERROR_MSG + JSONRPC_ERROR_SUFFIX)
                        .isError(true)
                        .build();
            }
        });
    }

    private McpSchema.CallToolResult buildIntrospectionResult(final String auth, final JahiaUser user)
            throws IOException, ServletException {
        final JsonNode step1 = executeInternalGraphQL(INTROSPECTION_STEP1, auth, user);
        if (step1 == null) {
            LOGGER.error("introspection step 1 returned no data");
            return McpSchema.CallToolResult.builder()
                    .addTextContent(JSONRPC_ERROR_PREFIX + INTERNAL_ERROR_MSG + JSONRPC_ERROR_SUFFIX)
                    .isError(true)
                    .build();
        }
        final JsonNode schemaNode = step1.path("data").path("__schema");
        final ObjectNode typeDetails = fetchTypeDetails(auth, user, collectTypeNames(schemaNode.path("types")));
        final ObjectNode result = OBJECT_MAPPER.createObjectNode();
        result.set("schema", schemaNode);
        result.set("types", typeDetails);
        return McpSchema.CallToolResult.builder()
                .addTextContent(OBJECT_MAPPER.writeValueAsString(result))
                .isError(false)
                .build();
    }

    private static List<String> collectTypeNames(final JsonNode typesArray) {
        final List<String> typeNames = new ArrayList<>();
        if (typesArray.isArray()) {
            for (final JsonNode t : typesArray) {
                final String name = t.path("name").asText("");
                final String kind = t.path("kind").asText("");
                if (!name.startsWith("__") && !"SCALAR".equals(kind) && !name.isEmpty()) {
                    typeNames.add(name);
                }
            }
        }
        return typeNames;
    }

    private ObjectNode fetchTypeDetails(final String auth, final JahiaUser user, final List<String> typeNames)
            throws IOException, ServletException {
        final ObjectNode typeDetails = OBJECT_MAPPER.createObjectNode();
        for (final String typeName : typeNames) {
            final JsonNode typeResult = executeInternalGraphQL(
                    String.format(INTROSPECTION_TYPE_QUERY, typeName), auth, user);
            if (typeResult != null) {
                final JsonNode typeNode = typeResult.path("data").path("__type");
                if (!typeNode.isMissingNode() && !typeNode.isNull()) {
                    typeDetails.set(typeName, typeNode);
                }
            }
        }
        return typeDetails;
    }

    /**
     * Tool: listSkills
     * Returns the name and description of every skill stored in JCR.
     */
    private McpStatelessServerFeatures.SyncToolSpecification listSkillsTool() {
        final McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("listSkills")
                .description("Returns the list of all skills available on this Jahia instance. "
                        + "Each skill has a name and a short description. "
                        + "Call getSkill(name) to retrieve the full Markdown instructions for a specific skill.")
                .inputSchema(JSON_MAPPER, "{\"type\":\"object\",\"properties\":{}}")
                .build();

        return new McpStatelessServerFeatures.SyncToolSpecification(tool, (ctx, req) -> {
            try {
                final List<McpSkillService.SkillEntry> skills = mcpSkillService.listSkills();
                final ArrayNode arr = OBJECT_MAPPER.createArrayNode();
                for (final McpSkillService.SkillEntry e : skills) {
                    final ObjectNode obj = OBJECT_MAPPER.createObjectNode();
                    obj.put("name", e.name);
                    obj.put("mcpName", e.mcpName);
                    obj.put("description", e.description);
                    arr.add(obj);
                }
                return McpSchema.CallToolResult.builder()
                        .addTextContent(OBJECT_MAPPER.writeValueAsString(arr))
                        .isError(false)
                        .build();
            } catch (Exception ex) {
                LOGGER.error("listSkills failed", ex);
                return McpSchema.CallToolResult.builder()
                        .addTextContent(JSONRPC_ERROR_PREFIX + INTERNAL_ERROR_MSG + JSONRPC_ERROR_SUFFIX)
                        .isError(true)
                        .build();
            }
        });
    }

    /**
     * Tool: getSkill
     * Returns the full Markdown content of a skill by name.
     */
    private McpStatelessServerFeatures.SyncToolSpecification getSkillTool() {
        final String schema = "{"
                + "\"type\":\"object\","
                + "\"properties\":{"
                + "  \"name\":{\"type\":\"string\",\"description\":\"Name of the skill to retrieve\"}"
                + "},"
                + "\"required\":[\"name\"]"
                + "}";

        final McpSchema.Tool tool = McpSchema.Tool.builder()
                .name("getSkill")
                .description("Returns the full Markdown instructions for a named skill. "
                        + "Call listSkills first to discover available skill names.")
                .inputSchema(JSON_MAPPER, schema)
                .build();

        return new McpStatelessServerFeatures.SyncToolSpecification(tool, (ctx, req) -> {
            final String name = (String) req.arguments().get("name");
            if (name == null || name.isBlank()) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent(JSONRPC_ERROR_PREFIX + "'name' argument is required" + JSONRPC_ERROR_SUFFIX)
                        .isError(true)
                        .build();
            }
            final McpSkillService.SkillEntry skill = mcpSkillService.getSkill(name);
            if (skill == null) {
                return McpSchema.CallToolResult.builder()
                        .addTextContent(JSONRPC_ERROR_PREFIX + "skill not found" + JSONRPC_ERROR_SUFFIX)
                        .isError(true)
                        .build();
            }
            return McpSchema.CallToolResult.builder()
                    .addTextContent(skill.content)
                    .isError(false)
                    .build();
        });
    }

    private JsonNode executeInternalGraphQL(String query, String auth, JahiaUser user) throws IOException, ServletException {
        final String requestBody = OBJECT_MAPPER.writeValueAsString(Map.of(QUERY_ARG, query));
        final HttpServletRequest requestWrapper = new McpHttpServletRequestWrapper(requestBody, auth);
        final StringWriter writer = new StringWriter();
        final McpHttpServletResponseWrapper responseWrapper = new McpHttpServletResponseWrapper(DUMMY_RESPONSE, writer);
        JCRSessionFactory.getInstance().setCurrentUser(user);
        try {
            gql.service(requestWrapper, responseWrapper);
        } finally {
            JcrSessionFilter.endRequest();
        }
        final String responseBody = writer.getBuffer().toString();
        if (responseBody.isEmpty()) {
            return null;
        }
        return OBJECT_MAPPER.readTree(responseBody);
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
            final ByteArrayInputStream bais = new ByteArrayInputStream(body);
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
                    // empty method as there's no async lifecycle to drive
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
        public AsyncContext startAsync() {
            throw new IllegalStateException(ASYNC_NOT_SUPPORTED);
        }

        @Override
        public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
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
