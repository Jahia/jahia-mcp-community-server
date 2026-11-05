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

import static org.assertj.core.api.Assertions.assertThat;

class InternalGraphQLClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static InternalGraphQLClient clientFor(final HttpServlet servlet) {
        InternalGraphQLClient client = new InternalGraphQLClient();
        client.setGraphQLServlet(servlet);
        return client;
    }

    @Test
    @DisplayName("forwards query, variables and Authorization header as a JSON POST")
    void forwards_request() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
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

        clientFor(servlet).execute("{ currentUser { name } }", Map.of("a", 1), "APIToken abc");

        assertThat(method.get()).isEqualTo("POST");
        assertThat(auth.get()).isEqualTo("APIToken abc");
        assertThat(MAPPER.readTree(body.get()).path("query").asText()).isEqualTo("{ currentUser { name } }");
        assertThat(MAPPER.readTree(body.get()).path("variables").path("a").asInt()).isEqualTo(1);
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

        InternalGraphQLClient.Response response = clientFor(servlet).execute("{ x }", null, null);

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

        assertThat(clientFor(servlet).execute("{ x }", null, null).body()).contains("Été — 日本");
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

        InternalGraphQLClient.Response response = clientFor(servlet).execute("{ x }", null, null);

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

        assertThat(clientFor(servlet).execute("{ x }", null, null).status()).isEqualTo(403);
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

        InternalGraphQLClient.Response response = clientFor(servlet).execute("{ x }", null, null);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.isHttpError()).isFalse();
    }
}
