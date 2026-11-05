package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jahia.community.mcp.McpSkillService;
import org.jahia.modules.mcp.spi.tool.ToolError;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.jahia.modules.mcp.spi.tool.ToolSafety;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpSkillService skills;

    @BeforeEach
    void setUp() {
        skills = mock(McpSkillService.class);
    }

    @Nested
    @DisplayName("community.skill.list")
    class ListTool {

        private SkillListTool tool;

        @BeforeEach
        void setUp() {
            tool = new SkillListTool(skills);
        }

        @Test
        @DisplayName("contract: namespaced name and READ_ONLY")
        void contract() {
            assertThat(tool.name()).isEqualTo("community.skill.list");
            assertThat(tool.safety()).isEqualTo(ToolSafety.READ_ONLY);
        }

        @Test
        @DisplayName("returns name, mcpName and description — not the content")
        void lists_skills() {
            ToolContextFixture f = ToolContextFixture.administrator();
            when(skills.listSkillHeaders()).thenReturn(List.of(
                    new McpSkillService.SkillEntry("hello-jahia", "hello-jahia", "Hello Jahia", "Greeting", "BODY")));

            ToolResult result = tool.execute(MAPPER.createObjectNode(), f.ctx);

            assertThat(result.payload().path("skills").get(0).path("name").asText()).isEqualTo("hello-jahia");
            assertThat(result.payload().path("skills").get(0).path("mcpName").asText()).isEqualTo("Hello Jahia");
            assertThat(result.payload().toString()).doesNotContain("BODY");
            assertThat(f.auditEvents).containsExactly("tool.execute.ok");
        }

        @Test
        @DisplayName("a skill in a sub-folder is listed by its relative path, the key skill.get expects")
        void nested_skill_listed_by_relative_path() {
            ToolContextFixture f = ToolContextFixture.administrator();
            when(skills.listSkillHeaders()).thenReturn(List.of(
                    new McpSkillService.SkillEntry("hello-jahia", "default/hello-jahia", "Hello Jahia", "Greeting", "BODY")));

            ToolResult result = tool.execute(MAPPER.createObjectNode(), f.ctx);

            assertThat(result.payload().path("skills").get(0).path("name").asText()).isEqualTo("default/hello-jahia");
        }

        @Test
        @DisplayName("non-administrator is refused")
        void non_admin_forbidden() {
            ToolResult result = tool.execute(MAPPER.createObjectNode(), ToolContextFixture.nonAdministrator().ctx);

            assertThat(result.error().code()).isEqualTo(ToolError.CODE_FORBIDDEN);
            verify(skills, never()).listSkills();
        }
    }

    @Nested
    @DisplayName("community.skill.get")
    class GetTool {

        private SkillGetTool tool;

        @BeforeEach
        void setUp() {
            tool = new SkillGetTool(skills);
        }

        private ObjectNode input(final String name) {
            ObjectNode in = MAPPER.createObjectNode();
            if (name != null) {
                in.put("name", name);
            }
            return in;
        }

        @Test
        @DisplayName("contract: namespaced name, READ_ONLY, schema requires name")
        void contract() {
            assertThat(tool.name()).isEqualTo("community.skill.get");
            assertThat(tool.safety()).isEqualTo(ToolSafety.READ_ONLY);
            assertThat(tool.inputSchema().path("required").toString()).contains("name");
        }

        @Test
        @DisplayName("returns the full skill including Markdown content")
        void returns_skill() {
            when(skills.getSkill("default/hello-jahia")).thenReturn(
                    new McpSkillService.SkillEntry("hello-jahia", "hello-jahia", "Hello Jahia", "Greeting", "# Hi"));

            ToolResult result = tool.execute(input("default/hello-jahia"), ToolContextFixture.administrator().ctx);

            assertThat(result.payload().path("content").asText()).isEqualTo("# Hi");
            assertThat(result.payload().path("name").asText()).isEqualTo("default/hello-jahia");
        }

        @Test
        @DisplayName("unknown skill → not_found")
        void unknown_skill() {
            ToolResult result = tool.execute(input("nope"), ToolContextFixture.administrator().ctx);

            assertThat(result.error().code()).isEqualTo(ToolError.CODE_NOT_FOUND);
        }

        @Test
        @DisplayName("blank name → validation error")
        void blank_name() {
            ToolContextFixture f = ToolContextFixture.administrator();

            assertThat(tool.execute(input(null), f.ctx).error().code()).isEqualTo(ToolError.CODE_VALIDATION);
            assertThat(tool.execute(input(""), f.ctx).error().code()).isEqualTo(ToolError.CODE_VALIDATION);
        }

        @Test
        @DisplayName("non-administrator is refused")
        void non_admin_forbidden() {
            ToolResult result = tool.execute(input("x"), ToolContextFixture.nonAdministrator().ctx);

            assertThat(result.error().code()).isEqualTo(ToolError.CODE_FORBIDDEN);
        }
    }
}
