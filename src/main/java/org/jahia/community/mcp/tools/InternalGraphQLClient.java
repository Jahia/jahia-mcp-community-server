package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jahia.community.mcp.access.CommunityMcpAccess;
import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.services.content.JCRSessionFactory;
import org.jahia.services.usermanager.JahiaUser;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpServletResponseWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Executes a GraphQL document in-process against Jahia's {@code OsgiGraphQLHttpServlet}.
 *
 * <p>No HTTP hop: a synthetic POST is handed to the servlet. The caller identity is taken
 * explicitly: {@link #execute(ToolContext, String, Object)} requires the {@code JahiaUser} bound by
 * mcp-server to be the context user, sets it as the current user around the servlet call and
 * restores the previous one afterwards, so JCR ACLs apply as for the caller whatever mcp-server does
 * internally. Do NOT call {@code JcrSessionFilter.endRequest()} here — mcp-server cleans the worker
 * thread after the tool returns, and ending the request mid-tool would drop the caller identity.
 * No credential header is forwarded: the synthetic request carries only the JSON body, and the
 * identity comes from the bound user alone.</p>
 */
@Component(service = InternalGraphQLClient.class)
public class InternalGraphQLClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final int HTTP_OK = 200;
    private static final int HTTP_ERROR_THRESHOLD = 400;

    /** Largest GraphQL response accepted (bytes); a bigger one is an error, not truncated data. */
    static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;

    private static final Logger LOGGER = LoggerFactory.getLogger(InternalGraphQLClient.class);

    private final HttpServlet graphQLServlet;

    /**
     * The servlet is the OSGi component {@code graphql.kickstart.servlet.OsgiGraphQLHttpServlet}
     * (graphql-java-kickstart, shipped by graphql-dxm-provider). The reference is optional and
     * greedy: when the component is missing or renamed by a graphql-dxm-provider upgrade, the tools
     * stay registered and answer with an explicit error (and this WARN names the target) instead of
     * silently disappearing from {@code tools/list}; the component is reactivated when the servlet
     * appears.
     */
    @Activate
    public InternalGraphQLClient(@Reference(service = HttpServlet.class,
            target = "(component.name=graphql.kickstart.servlet.OsgiGraphQLHttpServlet)",
            cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
            final HttpServlet graphQLServlet) {
        this.graphQLServlet = graphQLServlet;
        if (graphQLServlet == null) {
            LOGGER.warn("The GraphQL servlet (component.name=graphql.kickstart.servlet.OsgiGraphQLHttpServlet) "
                    + "is not available: community.graphql.* calls will fail until graphql-dxm-provider "
                    + "provides it (was the component renamed by an upgrade?)");
        }
    }

    /** The response exceeded {@link #MAX_RESPONSE_BYTES}: the document must select less. */
    public static final class ResponseTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;

        ResponseTooLargeException() {
            super("The GraphQL response exceeds " + MAX_RESPONSE_BYTES + " bytes");
        }
    }

    /** Outcome of an in-process GraphQL call: the HTTP status the servlet set, and its body. */
    public static final class Response {
        private final int status;
        private final String body;

        public Response(final int status, final String body) {
            this.status = status;
            this.body = body;
        }

        public int status() {
            return status;
        }

        public String body() {
            return body;
        }

        public boolean isHttpError() {
            return status >= HTTP_ERROR_THRESHOLD;
        }
    }

    /**
     * Runs {@code query} as the caller of {@code ctx}.
     *
     * @param variables optional variables map (may be {@code null})
     * @throws IllegalStateException if the bound JCR user is missing or is not {@code ctx.username()}
     * @throws ResponseTooLargeException if the response exceeds {@value #MAX_RESPONSE_BYTES} bytes
     * @throws IOException if the servlet is unavailable or the call fails
     */
    public Response execute(final ToolContext ctx, final String query, final Object variables)
            throws IOException, ServletException {
        if (graphQLServlet == null) {
            throw new IOException("The GraphQL servlet is not available");
        }
        final JahiaUser caller = CommunityMcpAccess.boundCaller(ctx).orElseThrow(() ->
                new IllegalStateException("The MCP caller identity is not bound to this thread"));
        final JCRSessionFactory sessionFactory = JCRSessionFactory.getInstance();
        final JahiaUser previous = sessionFactory.getCurrentUser();
        sessionFactory.setCurrentUser(caller);
        try {
            return dispatch(query, variables);
        } finally {
            sessionFactory.setCurrentUser(previous);
        }
    }

    private Response dispatch(final String query, final Object variables)
            throws IOException, ServletException {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("query", query);
        if (variables != null) {
            payload.put("variables", variables);
        }
        final byte[] requestBody = MAPPER.writeValueAsBytes(payload);
        final CapturingResponse response = new CapturingResponse();
        graphQLServlet.service(new SyntheticPostRequest(requestBody), response);
        // body() flushes the writer: the cap can be crossed by that very flush, so check afterwards.
        final String body = response.body();
        if (response.overflow) {
            throw new ResponseTooLargeException();
        }
        return new Response(response.status, body);
    }

    /** No-op delegate required by the servlet wrappers; every call returns a type default. */
    private static <T> T noOp(final Class<T> type) {
        return type.cast(Proxy.newProxyInstance(InternalGraphQLClient.class.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    final Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) {
                        return false;
                    }
                    if (rt == int.class) {
                        return 0;
                    }
                    if (rt == long.class) {
                        return 0L;
                    }
                    return null;
                }));
    }

    private static final class SyntheticPostRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        // graphql-dxm-provider stores per-request state (variables, fragments) as request attributes
        // and reads it back: they must round-trip, the no-op delegate would return null.
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();

        SyntheticPostRequest(final byte[] body) {
            super(noOp(HttpServletRequest.class));
            this.body = body;
        }

        @Override
        public String getMethod() {
            return "POST";
        }

        @Override
        public void setAttribute(final String name, final Object value) {
            if (value == null) {
                attributes.remove(name);
            } else {
                attributes.put(name, value);
            }
        }

        @Override
        public Object getAttribute(final String name) {
            return attributes.get(name);
        }

        @Override
        public void removeAttribute(final String name) {
            attributes.remove(name);
        }

        @Override
        public Enumeration<String> getAttributeNames() {
            return Collections.enumeration(new ArrayList<>(attributes.keySet()));
        }

        @Override
        public String getContentType() {
            return CONTENT_TYPE_JSON;
        }

        @Override
        public String getCharacterEncoding() {
            return StandardCharsets.UTF_8.name();
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }

        @Override
        public String getHeader(final String name) {
            if (HEADER_CONTENT_TYPE.equalsIgnoreCase(name)) {
                return CONTENT_TYPE_JSON;
            }
            return null;
        }

        @Override
        public Enumeration<String> getHeaders(final String name) {
            final String value = getHeader(name);
            return value == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(value));
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(List.of(HEADER_CONTENT_TYPE));
        }

        @Override
        public ServletInputStream getInputStream() {
            final ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(final ReadListener listener) {
                    // synchronous only — no async lifecycle to drive
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
    }

    /**
     * Captures status and body. Bytes from the output stream and characters from the writer go to
     * one UTF-8 byte buffer, so non-ASCII text is preserved. {@code setStatus}, {@code sendError} and
     * {@code getStatus} are recorded here, so an error status set by the servlet is detected.
     */
    private static final class CapturingResponse extends HttpServletResponseWrapper {
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private PrintWriter writer;
        private int status = HTTP_OK;
        private boolean overflow;

        CapturingResponse() {
            super(noOp(HttpServletResponse.class));
        }

        String body() {
            if (writer != null) {
                writer.flush();
            }
            return buffer.toString(StandardCharsets.UTF_8);
        }

        @Override
        public void setStatus(final int sc) {
            this.status = sc;
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public void sendError(final int sc) {
            this.status = sc;
        }

        @Override
        public void sendError(final int sc, final String msg) {
            this.status = sc;
        }

        @Override
        public String getCharacterEncoding() {
            return StandardCharsets.UTF_8.name();
        }

        @Override
        public ServletOutputStream getOutputStream() {
            return new ServletOutputStream() {
                @Override
                public void write(final int b) {
                    if (hasRoom(1)) {
                        buffer.write(b);
                    }
                }

                @Override
                public void write(final byte[] b, final int off, final int len) {
                    if (hasRoom(len)) {
                        buffer.write(b, off, len);
                    }
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setWriteListener(final WriteListener listener) {
                    // synchronous only
                }
            };
        }

        /** Bounded buffer: once the cap is hit, further bytes are dropped and {@code overflow} is set. */
        private boolean hasRoom(final int len) {
            if (overflow || buffer.size() + (long) len > MAX_RESPONSE_BYTES) {
                overflow = true;
                return false;
            }
            return true;
        }

        @Override
        public PrintWriter getWriter() {
            if (writer == null) {
                writer = new PrintWriter(new OutputStreamWriter(new OutputStream() {
                    @Override
                    public void write(final int b) {
                        if (hasRoom(1)) {
                            buffer.write(b);
                        }
                    }

                    @Override
                    public void write(final byte[] b, final int off, final int len) {
                        if (hasRoom(len)) {
                            buffer.write(b, off, len);
                        }
                    }
                }, StandardCharsets.UTF_8));
            }
            return writer;
        }

        @Override
        public void setContentLength(final int len) {
            // ignored: the body is buffered
        }

        @Override
        public void setContentLengthLong(final long len) {
            // ignored: the body is buffered
        }
    }
}
