package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.modules.mcp.spi.tool.ToolError;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ToolSupport")
class ToolSupportTest {

    @Test
    void should_load_a_bundled_schema() {
        assertThat(ToolSupport.loadSchema("community.skill.get").path("type").asText()).isEqualTo("object");
    }

    @Test
    void should_fail_clearly_when_the_schema_resource_is_missing() {
        assertThatThrownBy(() -> ToolSupport.loadSchema("community.nope"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("/schemas/community.nope.json");
    }

    @Test
    void should_hash_with_lower_case_hex_sha256() {
        assertThat(ToolSupport.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void should_parse_json_or_return_null() {
        assertThat(ToolSupport.parseJsonOrNull("{\"a\":1}").path("a").asInt()).isEqualTo(1);
        assertThat(ToolSupport.parseJsonOrNull(null)).isNull();
        assertThat(ToolSupport.parseJsonOrNull("   ")).isNull();
        assertThat(ToolSupport.parseJsonOrNull("{not json")).isNull();
    }

    @Test
    void should_sanitize_control_characters_and_bound_length() {
        assertThat(ToolSupport.sanitize(null, 10)).isEmpty();
        assertThat(ToolSupport.sanitize("a\r\nb\tc", 10)).isEqualTo("a b c");
        assertThat(ToolSupport.sanitize("abcdefghij", 4)).isEqualTo("abcd…");
        assertThat(ToolSupport.sanitize("abcd", 4)).isEqualTo("abcd");
    }

    @Test
    void should_audit_ok_and_error_results_with_extra_details() {
        final ToolContextFixture f = ToolContextFixture.administrator();
        final ToolResult ok = ToolResult.ok(mock(JsonNode.class));
        assertThat(ToolSupport.audited(f.ctx, "t", ok)).isSameAs(ok);
        ToolSupport.audited(f.ctx, "t", ToolResult.error(ToolError.CODE_NOT_FOUND, "m", "h"), Map.of("k", "v"));
        assertThat(f.auditEvents).containsExactly(ToolSupport.EVENT_OK, ToolSupport.EVENT_ERROR);
        assertThat(f.auditDetails.get(1)).containsEntry("code", ToolError.CODE_NOT_FOUND).containsEntry("k", "v")
                .containsEntry("user", "root");
    }

    @Test
    void should_not_propagate_audit_backend_failures() {
        final ToolContext ctx = mock(ToolContext.class);
        when(ctx.username()).thenReturn("root");
        when(ctx.audit()).thenThrow(new IllegalStateException("down"));
        final ToolResult ok = ToolResult.ok(mock(JsonNode.class));
        assertThat(ToolSupport.audited(ctx, "t", ok)).isSameAs(ok);
    }

    @Test
    void should_wrap_io_failures_while_reading_a_schema() {
        final java.io.InputStream broken = new java.io.InputStream() {
            @Override
            public int read() throws java.io.IOException {
                throw new java.io.IOException("boom");
            }
        };
        assertThatThrownBy(() -> ToolSupport.loadSchema("x", r -> broken))
                .isInstanceOf(java.io.UncheckedIOException.class).hasMessageContaining("/schemas/x.json");
    }
}
