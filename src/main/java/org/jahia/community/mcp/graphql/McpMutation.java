package org.jahia.community.mcp.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.community.mcp.McpSkillService;
import org.jahia.community.mcp.config.McpConfigService;
import org.jahia.modules.graphql.provider.dxm.DataFetchingException;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;
import org.jahia.osgi.BundleUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;

@GraphQLName("CommunityMcpMutation")
@GraphQLDescription("Community MCP mutations")
public class McpMutation {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpMutation.class);
    private static final String SKILL_SERVICE_UNAVAILABLE = "The MCP skill service is not available";

    @GraphQLField
    @GraphQLName("saveSettings")
    @GraphQLDescription("Saves the MCP operation whitelist. Returns true on success; fails with a GraphQL error "
            + "naming the offending entry when an entry is malformed, and with a generic error when the "
            + "configuration cannot be stored.")
    @GraphQLRequiresPermission("admin")
    public Boolean saveSettings(
            @GraphQLName("whitelist") @GraphQLNonNull @GraphQLDescription("Operations to allow, as 'query:<path>' or "
                    + "'mutation:<path>' (an unprefixed path is query-only). An empty list is the read-only "
                    + "default: every query is allowed, no mutation and no subscription") List<String> whitelist) {
        final McpConfigService config = BundleUtils.getOsgiService(McpConfigService.class, null);
        if (config == null) {
            LOGGER.error("McpConfigService is not available (is the org.jahia.community.mcp configuration present?)");
            throw new DataFetchingException("The MCP configuration service is not available: "
                    + "check that the org.jahia.community.mcp configuration exists");
        }
        try {
            config.saveWhitelist(whitelist);
            return Boolean.TRUE;
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Rejected MCP whitelist: {}", e.getMessage());
            throw new DataFetchingException(e.getMessage());
        } catch (IOException e) {
            LOGGER.error("Error saving MCP settings to OSGi configuration", e);
            throw new DataFetchingException("The MCP whitelist could not be stored, see the server log");
        }
    }

    @GraphQLField
    @GraphQLName("saveSkill")
    @GraphQLDescription("Creates or updates an MCP skill in JCR")
    @GraphQLRequiresPermission("admin")
    public Boolean saveSkill(
            @GraphQLName("name") @GraphQLNonNull @GraphQLDescription("Skill key: the path relative to the skills root, "
                    + "e.g. 'my-skill' or 'team/my-skill' (intermediate folders are created)") String name,
            @GraphQLName("mcpName") @GraphQLDescription("Human-readable display name for the skill") String mcpName,
            @GraphQLName("description") @GraphQLDescription("Short description of what the skill does") String description,
            @GraphQLName("content") @GraphQLNonNull @GraphQLDescription("Full Markdown content — instructions the AI should follow") String content) {
        final McpSkillService service = BundleUtils.getOsgiService(McpSkillService.class, null);
        if (service == null) {
            LOGGER.error(SKILL_SERVICE_UNAVAILABLE);
            return Boolean.FALSE;
        }
        return service.saveSkill(name, mcpName, description, content);
    }

    @GraphQLField
    @GraphQLName("deleteSkill")
    @GraphQLDescription("Deletes an MCP skill from JCR")
    @GraphQLRequiresPermission("admin")
    public Boolean deleteSkill(
            @GraphQLName("name") @GraphQLNonNull @GraphQLDescription("Skill key (the path returned by the skills query)") String name) {
        final McpSkillService service = BundleUtils.getOsgiService(McpSkillService.class, null);
        if (service == null) {
            LOGGER.error(SKILL_SERVICE_UNAVAILABLE);
            return Boolean.FALSE;
        }
        return service.deleteSkill(name);
    }
}
