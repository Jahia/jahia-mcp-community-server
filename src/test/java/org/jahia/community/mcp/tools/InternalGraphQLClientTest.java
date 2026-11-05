package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.jahia.community.mcp.access.CallerFixture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InternalGraphQLClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static InternalGraphQLClient clientFor(final HttpServlet servlet) {
        return new InternalGraphQLClient(servlet);
    }

    /** Runs {@code query} through the caller-bound entry point as a bound administrator. */
    private static InternalGraphQLClient.Response run(final HttpServlet servlet, final String query,
                                                      final Object variables) throws Exception {
        try (CallerFixture caller = CallerFixture.administrator()) {
            return clientFor(servlet).execute(caller.ctx, query, variables);
        }
    }

    @Test
    @DisplayName("forwards query and variables as a JSON POST, never the caller's Authorization header")
    void forwards_request() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>("untouched");
        AtomicReference<String> method = new AtomicReference<>();
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                method.set(req.getMethod());
                auth.set(req.getHeader("Authorization"));
                body.set(req.getReader().lines().collect(Collectors.joining()));
                resp.getWriter().write("{\"data\":{}}");
            }
        };

        try (CallerFixture caller = CallerFixture.administrator()) {
            HttpServletRequest incoming = org.mockito.Mockito.mock(HttpServletRequest.class);
            org.mockito.Mockito.when(incoming.getHeader("Authorization")).thenReturn("APIToken abc");
            org.mockito.Mockito.when(caller.ctx.request()).thenReturn(java.util.Optional.of(incoming));

            clientFor(servlet).execute(caller.ctx, "{ currentUser { name } }", Map.of("a", 1));
        }

        assertThat(method.get()).isEqualTo("POST");
        assertThat(auth.get()).as("the identity comes from the bound user, not a credential header").isNull();
        assertThat(MAPPER.readTree(body.get()).path("query").asText()).isEqualTo("{ currentUser { name } }");
        assertThat(MAPPER.readTree(body.get()).path("variables").path("a").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("request attributes round-trip (the dxm provider stores variables and fragments there)")
    void attributes_round_trip() throws Exception {
        final AtomicReference<Object> read = new AtomicReference<>();
        final AtomicReference<Object> afterRemove = new AtomicReference<>("untouched");
        final AtomicReference<java.util.List<String>> names = new AtomicReference<>();
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                req.setAttribute("graphQLVariables", Map.of("a", 1));
                req.setAttribute("dropped", null);
                req.setAttribute("tmp", "x");
                names.set(java.util.Collections.list(req.getAttributeNames()));
                req.removeAttribute("tmp");
                read.set(req.getAttribute("graphQLVariables"));
                afterRemove.set(req.getAttribute("tmp"));
                resp.getWriter().write("{\"data\":{}}");
            }
        };

        run(servlet, "{ x }", null);

        assertThat(read.get()).isEqualTo(Map.of("a", 1));
        assertThat(afterRemove.get()).isNull();
        assertThat(names.get()).containsExactlyInAnyOrder("graphQLVariables", "tmp");
    }

    @Test
    @DisplayName("multi-byte UTF-8 written through the output stream is decoded intact")
    void utf8_output_stream_is_decoded() throws Exception {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getOutputStream().write("{\"data\":{\"title\":\"Éléphant 日本\"}}".getBytes(StandardCharsets.UTF_8));
            }
        };

        InternalGraphQLClient.Response response = run(servlet, "{ x }", null);

        assertThat(response.body()).contains("Éléphant 日本");
    }

    @Test
    @DisplayName("multi-byte UTF-8 written through the writer is decoded intact")
    void utf8_writer_is_decoded() throws Exception {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write("{\"t\":\"Été — 日本\"}");
            }
        };

        assertThat(run(servlet, "{ x }", null).body()).contains("Été — 日本");
    }

    @Test
    @DisplayName("HTTP status set by the GraphQL servlet is captured (was always 0 before)")
    void status_is_captured() throws Exception {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setStatus(400);
                resp.getWriter().write("{\"errors\":[{\"message\":\"bad\"}]}");
            }
        };

        InternalGraphQLClient.Response response = run(servlet, "{ x }", null);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.isHttpError()).isTrue();
    }

    @Test
    @DisplayName("sendError is captured as the response status")
    void send_error_is_captured() throws Exception {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.sendError(403, "nope");
            }
        };

        assertThat(run(servlet, "{ x }", null).status()).isEqualTo(403);
    }

    @Test
    @DisplayName("default status is 200 when the servlet does not set one")
    void default_status_is_ok() throws Exception {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getWriter().write("{}");
            }
        };

        InternalGraphQLClient.Response response = run(servlet, "{ x }", null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isHttpError()).isFalse();
    }

    // ── identity-bound execute(ToolContext, …) ────────────────────────────────

    @Test
    @DisplayName("the caller is set as the current user around the call and the previous one restored")
    void caller_bound_during_call_and_restored() throws Exception {
        try (CallerFixture caller = CallerFixture.administrator()) {
            org.jahia.services.usermanager.JahiaUser previous = org.mockito.Mockito.mock(org.jahia.services.usermanager.JahiaUser.class);
            // 1st read: the identity check; 2nd read: the user to restore afterwards
            org.mockito.Mockito.when(caller.sessionFactory.getCurrentUser()).thenReturn(caller.user, previous);
            AtomicReference<String> auth = new AtomicReference<>("untouched");
            HttpServlet servlet = new HttpServlet() {
                @Override
                protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                    auth.set(req.getHeader("Authorization"));
                    resp.getWriter().write("{\"data\":{}}");
                }
            };

            InternalGraphQLClient.Response response = clientFor(servlet).execute(caller.ctx, "{ x }", Map.of("a", 1));

            assertThat(response.status()).isEqualTo(200);
            assertThat(auth.get()).as("no credential header is forwarded").isNull();
            org.mockito.InOrder order = org.mockito.Mockito.inOrder(caller.sessionFactory);
            order.verify(caller.sessionFactory).setCurrentUser(caller.user);
            order.verify(caller.sessionFactory).setCurrentUser(previous);
        }
    }

    @Test
    @DisplayName("the previous user is restored even when the servlet throws")
    void restored_on_failure() {
        try (CallerFixture caller = CallerFixture.administrator()) {
            HttpServlet servlet = new HttpServlet() {
                @Override
                protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                    throw new IOException("boom");
                }
            };

            final InternalGraphQLClient client = clientFor(servlet);
            assertThatThrownBy(() -> client.execute(caller.ctx, "{ x }", null)).isInstanceOf(IOException.class);

            org.mockito.Mockito.verify(caller.sessionFactory, org.mockito.Mockito.times(2)).setCurrentUser(caller.user);
        }
    }

    @Test
    @DisplayName("without a bound caller (or a different one) nothing is executed")
    void unbound_caller_refused() {
        try (CallerFixture caller = CallerFixture.administrator()) {
            org.mockito.Mockito.when(caller.sessionFactory.getCurrentUser()).thenReturn(null);
            AtomicReference<Boolean> called = new AtomicReference<>(false);
            HttpServlet servlet = new HttpServlet() {
                @Override
                protected void service(HttpServletRequest req, HttpServletResponse resp) {
                    called.set(true);
                }
            };

            final InternalGraphQLClient client = clientFor(servlet);
            assertThatThrownBy(() -> client.execute(caller.ctx, "{ x }", null))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(called.get()).isFalse();
            org.mockito.Mockito.verify(caller.sessionFactory, org.mockito.Mockito.never())
                    .setCurrentUser(org.mockito.ArgumentMatchers.any());
        }
    }

    // ── response cap ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("a response larger than the cap is an error, not silently truncated data (output stream)")
    void oversized_stream_response_rejected() {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                byte[] chunk = new byte[1024 * 1024];
                java.util.Arrays.fill(chunk, (byte) 'a');
                for (int i = 0; i <= InternalGraphQLClient.MAX_RESPONSE_BYTES / chunk.length; i++) {
                    resp.getOutputStream().write(chunk, 0, chunk.length);
                }
                resp.getOutputStream().write('x');
            }
        };

        assertThatThrownBy(() -> run(servlet, "{ x }", null))
                .isInstanceOf(IOException.class).hasMessageContaining("exceeds");
    }

    @Test
    @DisplayName("a response larger than the cap is an error (writer)")
    void oversized_writer_response_rejected() {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                String chunk = "a".repeat(1024 * 1024);
                for (int i = 0; i <= InternalGraphQLClient.MAX_RESPONSE_BYTES / chunk.length(); i++) {
                    resp.getWriter().write(chunk);
                }
                resp.getWriter().flush();
            }
        };

        assertThatThrownBy(() -> run(servlet, "{ x }", null)).isInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("PRB-4: a writer that crosses the cap only in the final flush still fails, never truncated success")
    void unflushed_writer_overflow_rejected() {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                // Reaches the cap through the output stream, then leaves 500 chars in the writer buffer.
                resp.getOutputStream().write(new byte[InternalGraphQLClient.MAX_RESPONSE_BYTES - 100]);
                resp.getWriter().write("b".repeat(500));
            }
        };

        assertThatThrownBy(() -> run(servlet, "{ x }", null))
                .isInstanceOf(IOException.class).hasMessageContaining("exceeds");
    }

    @Test
    @DisplayName("a response exactly at the cap is accepted; single-byte writes are bounded too")
    void response_at_cap_accepted() throws Exception {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.getOutputStream().write(new byte[InternalGraphQLClient.MAX_RESPONSE_BYTES - 1]);
                resp.getOutputStream().write('x');
            }
        };

        assertThat(run(servlet, "{ x }", null).body()).hasSize(InternalGraphQLClient.MAX_RESPONSE_BYTES);
    }

    @Test
    @DisplayName("the synthetic request exposes POST/JSON metadata and a readable body; the response ignores content length")
    void synthetic_request_and_response_surface() throws Exception {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                resp.setContentLength(5);
                resp.setContentLengthLong(5L);
                assertThat(req.getContentType()).isEqualTo("application/json");
                assertThat(req.getCharacterEncoding()).isEqualTo("UTF-8");
                assertThat(req.getContentLength()).isPositive();
                assertThat(req.getContentLengthLong()).isPositive();
                assertThat(req.getHeader("Content-Type")).isEqualTo("application/json");
                assertThat(req.getHeader("X-Other")).isNull();
                assertThat(java.util.Collections.list(req.getHeaders("X-Other"))).isEmpty();
                assertThat(java.util.Collections.list(req.getHeaders("Content-Type"))).containsExactly("application/json");
                assertThat(java.util.Collections.list(req.getHeaderNames())).containsExactly("Content-Type");
                assertThat(req.isAsyncSupported()).isFalse();
                javax.servlet.ServletInputStream in = req.getInputStream();
                assertThat(in.isReady()).isTrue();
                assertThat(in.isFinished()).isFalse();
                in.setReadListener(null);
                assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).contains("\"query\"");
                assertThat(in.isFinished()).isTrue();
                javax.servlet.ServletOutputStream out = resp.getOutputStream();
                assertThat(out.isReady()).isTrue();
                out.setWriteListener(null);
                assertThat(resp.getCharacterEncoding()).isEqualTo("UTF-8");
                assertThat(resp.getStatus()).isEqualTo(200);
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }
        };

        assertThat(run(servlet, "{ x }", null).body()).isEqualTo("{}");
    }

    @Test
    @DisplayName("noOp request/response defaults never return null for primitives")
    void response_wrapper_defaults() throws Exception {
        HttpServlet servlet = new HttpServlet() {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) {
                // delegates to the no-op proxy: primitives get type defaults
                assertThat(req.getRemotePort()).isZero();
                assertThat(req.isSecure()).isFalse();
                assertThat(req.getContentLengthLong()).isPositive();
                assertThat(req.getDateHeader("x")).isZero();
            }
        };

        assertThat(run(servlet, "{ x }", null).status()).isEqualTo(200);
    }
}
