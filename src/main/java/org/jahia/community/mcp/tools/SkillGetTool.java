package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jahia.community.mcp.McpSkillService;
import org.jahia.community.mcp.access.CommunityMcpAccess;
import org.jahia.modules.mcp.spi.tool.JahiaMcpTool;
import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.modules.mcp.spi.tool.ToolError;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.jahia.modules.mcp.spi.tool.ToolSafety;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import java.util.Optional;
import java.util.Set;

/** {@code community.skill.get} — full Markdown content of one skill. */
@Component(service = JahiaMcpTool.class, immediate = true)
public class SkillGetTool implements JahiaMcpTool {

    static final String NAME = "community.skill.get";
    private static final JsonNode SCHEMA = ToolSupport.loadSchema(NAME);

    private volatile McpSkillService skillService;

    @Reference
    public void setSkillService(final McpSkillService skillService) {
        this.skillService = skillService;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Read the full Markdown instructions of a Jahia skill. Use a name returned by "
                + "community.skill.list (it may include a sub-folder, e.g. default/hello-jahia).";
    }

    @Override
    public JsonNode inputSchema() {
        return SCHEMA;
    }

    @Override
    public ToolSafety safety() {
        return ToolSafety.READ_ONLY;
    }

    @Override
    public Set<String> tags() {
        return Set.of("community");
    }

    @Override
    public ToolResult execute(final JsonNode input, final ToolContext ctx) {
        return ToolSupport.audited(ctx, NAME, run(input, ctx));
    }

    private ToolResult run(final JsonNode input, final ToolContext ctx) {
        final Optional<ToolResult> denied = CommunityMcpAccess.requireAdministrator(ctx);
        if (denied.isPresent()) {
            return denied.get();
        }
        final JsonNode nameNode = input.path("name");
        if (!nameNode.isTextual() || nameNode.asText().isBlank()) {
            return ToolResult.error(ToolError.CODE_VALIDATION, "'name' is required.",
                    "Pass a skill name returned by community.skill.list.");
        }
        final String name = nameNode.asText();
        // getSkill validates the name (charset, no traversal) and returns null when rejected or absent.
        final McpSkillService.SkillEntry skill = skillService.getSkill(name);
        if (skill == null) {
            return ToolResult.error(ToolError.CODE_NOT_FOUND, "Skill '" + name + "' not found.",
                    "Call community.skill.list to see the available skill names.");
        }
        return ToolResult.ok(ToolSupport.MAPPER.createObjectNode()
                .put("name", name)
                .put("mcpName", skill.mcpName)
                .put("description", skill.description)
                .put("content", skill.content));
    }
}
