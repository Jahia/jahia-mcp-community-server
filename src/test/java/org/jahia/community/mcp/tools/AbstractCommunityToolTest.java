package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.modules.mcp.spi.tool.ToolError;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.jahia.modules.mcp.spi.tool.ToolSafety;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("AbstractCommunityTool template method")
class AbstractCommunityToolTest {

    private static final class Probe extends AbstractCommunityTool {
        final AtomicInteger runs = new AtomicInteger();

        Probe(final String name) {
            super(name);
        }

        @Override
        public String description() {
            return "probe";
        }

        @Override
        public ToolSafety safety() {
            return ToolSafety.READ_ONLY;
        }

        @Override
        protected ToolResult doExecute(final JsonNode input, final ToolContext ctx) {
            runs.incrementAndGet();
            return ToolResult.ok(ToolSupport.MAPPER.createObjectNode());
        }

        @Override
        protected Map<String, Object> auditDetails(final JsonNode input) {
            return Map.of("probe", true);
        }
    }

    @Test
    void should_block_non_administrators_before_doExecute_and_audit_the_denial() {
        final Probe tool = new Probe("community.skill.list");
        final ToolContextFixture f = ToolContextFixture.nonAdministrator();

        final ToolResult result = tool.execute(ToolSupport.MAPPER.createObjectNode(), f.ctx);

        assertThat(result.isError()).isTrue();
        assertThat(result.error().code()).isEqualTo(ToolError.CODE_FORBIDDEN);
        assertThat(tool.runs).hasValue(0);
        assertThat(f.auditEvents).containsExactly(ToolSupport.EVENT_ERROR);
        assertThat(f.auditDetails.get(0)).doesNotContainKey("probe");
    }

    @Test
    void should_run_and_audit_for_administrators_with_the_subclass_details() {
        final Probe tool = new Probe("community.skill.list");
        final ToolContextFixture f = ToolContextFixture.administrator();

        assertThat(tool.execute(ToolSupport.MAPPER.createObjectNode(), f.ctx).isOk()).isTrue();

        assertThat(tool.runs).hasValue(1);
        assertThat(f.auditEvents).containsExactly(ToolSupport.EVENT_OK);
        assertThat(f.auditDetails.get(0)).containsEntry("probe", true);
        assertThat(tool.name()).isEqualTo("community.skill.list");
        assertThat(tool.inputSchema().path("type").asText()).isEqualTo("object");
        assertThat(tool.tags()).containsExactly("community");
    }

    @Test
    void should_not_let_subclasses_override_execute_name_or_schema() throws Exception {
        for (final String m : new String[] {"name", "inputSchema"}) {
            assertThat(Modifier.isFinal(AbstractCommunityTool.class.getMethod(m).getModifiers())).isTrue();
        }
        assertThat(Modifier.isFinal(AbstractCommunityTool.class
                .getMethod("execute", JsonNode.class, ToolContext.class).getModifiers())).isTrue();
    }

    @Test
    void should_fail_construction_when_the_schema_is_missing() {
        assertThatThrownBy(() -> new Probe("community.missing")).isInstanceOf(IllegalStateException.class);
    }
}
