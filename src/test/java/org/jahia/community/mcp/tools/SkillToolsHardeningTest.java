package org.jahia.community.mcp.tools;

import org.jahia.community.mcp.McpSkillService;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("skill tools — UGC tag, rejected names, access")
class SkillToolsHardeningTest {

    private final McpSkillService skills = mock(McpSkillService.class);

    @Test
    void should_tag_both_skill_tools_as_reading_ugc() {
        assertThat(new SkillGetTool(skills).tags()).containsExactlyInAnyOrder("community", "reads-ugc");
        assertThat(new SkillListTool(skills).tags()).containsExactlyInAnyOrder("community", "reads-ugc");
        assertThat(new SkillGetTool(skills).description()).contains("community.skill.list");
        assertThat(new SkillListTool(skills).description()).contains("community.skill.get");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../etc/passwd", "a//b", "/abs", "bad name", "x\u0000y"})
    void should_report_not_found_for_names_the_service_rejects(final String name) {
        when(skills.getSkill(anyString())).thenReturn(null);

        final ToolResult result = new SkillGetTool(skills)
                .execute(ToolSupport.MAPPER.createObjectNode().put("name", name),
                        ToolContextFixture.administrator().ctx);

        assertThat(result.error().code()).isEqualTo("not_found");
        assertThat(result.error().message()).doesNotContain("\u0000");
    }

    @Test
    void should_reject_blank_or_missing_names() {
        final SkillGetTool tool = new SkillGetTool(skills);
        final ToolContextFixture f = ToolContextFixture.administrator();
        assertThat(tool.execute(ToolSupport.MAPPER.createObjectNode(), f.ctx).error().code()).isEqualTo("validation");
        assertThat(tool.execute(ToolSupport.MAPPER.createObjectNode().put("name", " "), f.ctx).error().code())
                .isEqualTo("validation");
    }

    @Test
    void should_deny_non_administrators_on_both_tools() {
        final ToolContextFixture f = ToolContextFixture.nonAdministrator();
        assertThat(new SkillListTool(skills).execute(ToolSupport.MAPPER.createObjectNode(), f.ctx).isError())
                .isTrue();
        assertThat(new SkillGetTool(skills)
                .execute(ToolSupport.MAPPER.createObjectNode().put("name", "a"), f.ctx).isError()).isTrue();
    }
}
