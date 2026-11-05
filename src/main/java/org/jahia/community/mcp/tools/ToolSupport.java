package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Small helpers shared by the community tools: schema loading, auditing, header access. */
final class ToolSupport {

    static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolSupport.class);
    static final String EVENT_OK = "tool.execute.ok";
    static final String EVENT_ERROR = "tool.execute.error";

    /** Upper bound for any caller- or server-supplied text echoed into an error message. */
    static final int MAX_ECHO_CHARS = 300;

    private ToolSupport() {
    }

    /** Loads {@code schemas/<toolName>.json} from this bundle. */
    static JsonNode loadSchema(final String toolName) {
        return loadSchema(toolName, ToolSupport.class::getResourceAsStream);
    }

    static JsonNode loadSchema(final String toolName, final Function<String, InputStream> opener) {
        final String resource = "/schemas/" + toolName + ".json";
        try (InputStream in = opener.apply(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing tool schema resource " + resource);
            }
            return MAPPER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable tool schema resource " + resource, e);
        }
    }

    /** Records the mandatory audit event for {@code result} and returns it unchanged. */
    static ToolResult audited(final ToolContext ctx, final String toolName, final ToolResult result) {
        return audited(ctx, toolName, result, Map.of());
    }

    /**
     * Records the mandatory audit event, with {@code extra} details, and returns {@code result}
     * unchanged. An audit backend failure is logged, never propagated: the operation has already
     * run, and reporting it as failed would invite the client to retry a completed mutation.
     */
    static ToolResult audited(final ToolContext ctx, final String toolName, final ToolResult result,
            final Map<String, Object> extra) {
        final Map<String, Object> details = new LinkedHashMap<>();
        details.put("tool", toolName);
        details.put("user", ctx.username());
        if (result.isError()) {
            details.put("code", result.error().code());
        }
        details.putAll(extra);
        try {
            ctx.audit().recordEvent(result.isOk() ? EVENT_OK : EVENT_ERROR, details);
        } catch (RuntimeException ex) {
            LOGGER.error("Audit recording failed for tool='{}', details={}", toolName, details, ex);
        }
        return result;
    }

    /** Lower-case hex SHA-256 of {@code text} (UTF-8) — identifies a document without storing it. */
    static String sha256Hex(final String text) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }

    /** Parses {@code body} as JSON, or {@code null} when it is empty or not JSON. */
    static JsonNode parseJsonOrNull(final String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(body);
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Makes untrusted text safe to echo in an error: control characters (including line breaks)
     * become spaces, and the result is cut to {@code maxChars} with an ellipsis.
     */
    static String sanitize(final String text, final int maxChars) {
        if (text == null) {
            return "";
        }
        final String cleaned = text.replaceAll("\\p{Cntrl}+", " ").strip();
        return cleaned.length() <= maxChars ? cleaned : cleaned.substring(0, maxChars) + "…";
    }
}
