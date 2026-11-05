package org.jahia.community.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jahia.community.mcp.McpSkillService;
import org.jahia.community.mcp.access.CommunityMcpAccess;
import org.jahia.modules.mcp.spi.tool.JahiaMcpTool;
import org.jahia.modules.mcp.spi.tool.ToolContext;
import org.jahia.modules.mcp.spi.tool.ToolResult;
import org.jahia.modules.mcp.spi.tool.ToolSafety;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

import java.util.Optional;
import java.util.Set;

/** {@code community.skill.list} — names and descriptions of the skills stored in JCR. */
@Component(service = JahiaMcpTool.class, immediate = true)
public class SkillListTool implements JahiaMcpTool {

    static final String NAME = "community.skill.list";
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
        return "List the Jahia-specific skills (Markdown instruction documents) available on this server, "
                + "with their name and description. Call community.skill.get with a name to read one.";
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
        return ToolSupport.audited(ctx, NAME, run(ctx));
    }

    private ToolResult run(final ToolContext ctx) {
        final Optional<ToolResult> denied = CommunityMcpAccess.requireAdministrator(ctx);
        if (denied.isPresent()) {
            return denied.get();
        }
        final ObjectNode result = ToolSupport.MAPPER.createObjectNode();
        final ArrayNode skills = result.putArray("skills");
        for (final McpSkillService.SkillEntry e : skillService.listSkills()) {
            skills.addObject()
                    .put("name", e.path)
                    .put("mcpName", e.mcpName)
                    .put("description", e.description);
        }
        return ToolResult.ok(result);
    }
}
