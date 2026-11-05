package org.jahia.community.mcp.tools;

import org.jahia.community.mcp.McpSkillService;
import org.jahia.modules.mcp.spi.tool.ToolSafety;

import java.util.Set;

/** Shared shape of the read-only skill tools. Skill bodies are editor-authored content. */
abstract class AbstractSkillTool extends AbstractCommunityTool {

    protected final McpSkillService skillService;

    protected AbstractSkillTool(final String name, final McpSkillService skillService) {
        super(name);
        this.skillService = skillService;
    }

    @Override
    public ToolSafety safety() {
        return ToolSafety.READ_ONLY;
    }

    @Override
    public Set<String> tags() {
        // Skill content is user-authored: prompt-injection exposure, like any UGC-reading tool.
        return Set.of("community", "reads-ugc");
    }
}
