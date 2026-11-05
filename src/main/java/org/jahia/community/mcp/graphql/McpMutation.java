package org.jahia.community.mcp.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import org.jahia.community.mcp.LogSupport;
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
    @GraphQLDescription("Saves the MCP operation whitelist on this cluster node only (it is not replicated). "
            + "Returns true on success; fails with a GraphQL error naming the offending entry when an entry is "
            + "malformed or names a never-allowed path (mutation:communityMcp, mutation:admin.jahia.configuration, "
            + "mutation:admin.personalApiTokens, or any mutation path with a grantRoles or revokeRoles segment), "
            + "and with a generic error when the configuration is missing or cannot be stored.")
    @GraphQLRequiresPermission("admin")
    public Boolean saveSettings(
            @GraphQLName("whitelist") @GraphQLNonNull @GraphQLDescription("Operations to allow, as 'query:<path>' or "
                    + "'mutation:<path>' (an unprefixed path is query-only). An empty list is the read-only "
                    + "default: every query is allowed except those under 'admin' (needs query:admin), no mutation and no subscription") List<String> whitelist) {
        final McpConfigService config = BundleUtils.getOsgiService(McpConfigService.class, null);
        if (config == null) {
            LOGGER.error("McpConfigService is not available (is the org.jahia.community.mcp configuration present?)");
            throw new DataFetchingException("The MCP configuration service is not available: "
                    + "check that the org.jahia.community.mcp configuration exists");
        }
        try {
            config.saveWhitelist(whitelist);
            if (LOGGER.isInfoEnabled()) {
                LOGGER.info("MCP whitelist updated by user '{}': {} entries", LogSupport.actingUser(),
                        whitelist.size());
            }
            return Boolean.TRUE;
        } catch (IllegalArgumentException e) {
            // McpConfigService already logged the rejection; its message is client-safe.
            throw new DataFetchingException(e.getMessage());
        } catch (IOException e) {
            LOGGER.error("Error saving MCP settings to OSGi configuration", e);
            throw new DataFetchingException("The MCP whitelist could not be stored, see the server log");
        }
    }

    @GraphQLField
    @GraphQLName("saveSkill")
    @GraphQLDescription("Creates or updates an MCP skill in JCR. Returns true on success; fails with a GraphQL "
            + "error giving the reason when the name or content is invalid or the skill cannot be stored.")
    @GraphQLRequiresPermission("admin")
    public Boolean saveSkill(
            @GraphQLName("name") @GraphQLNonNull @GraphQLDescription("Skill key: the path relative to the skills root, "
                    + "e.g. 'my-skill' or 'team/my-skill' (intermediate folders are created)") String name,
            @GraphQLName("mcpName") @GraphQLDescription("Human-readable display title for the skill (the key is 'name')") String mcpName,
            @GraphQLName("description") @GraphQLDescription("Short description of what the skill does") String description,
            @GraphQLName("content") @GraphQLNonNull @GraphQLDescription("Full Markdown content: guidance for an AI client to evaluate, not commands to obey") String content) {
        final McpSkillService service = BundleUtils.getOsgiService(McpSkillService.class, null);
        if (service == null) {
            LOGGER.error(SKILL_SERVICE_UNAVAILABLE);
            throw new DataFetchingException(SKILL_SERVICE_UNAVAILABLE);
        }
        validateName(name);
        if (content.length() > McpSkillService.MAX_CONTENT_CHARS) {
            throw new DataFetchingException("The skill content is too long: at most "
                    + McpSkillService.MAX_CONTENT_CHARS + " characters are accepted");
        }
        if (!service.saveSkill(name, mcpName, description, content)) {
            throw new DataFetchingException("The skill could not be saved: a folder or a node that is not a skill "
                    + "may already use that name, or the repository failed (see the server log)");
        }
        return Boolean.TRUE;
    }

    @GraphQLField
    @GraphQLName("deleteSkill")
    @GraphQLDescription("Deletes an MCP skill from JCR. Returns true on success; fails with a GraphQL error when "
            + "the name is invalid or no such skill exists.")
    @GraphQLRequiresPermission("admin")
    public Boolean deleteSkill(
            @GraphQLName("name") @GraphQLNonNull @GraphQLDescription("Skill key (the path returned by the skills query)") String name) {
        final McpSkillService service = BundleUtils.getOsgiService(McpSkillService.class, null);
        if (service == null) {
            LOGGER.error(SKILL_SERVICE_UNAVAILABLE);
            throw new DataFetchingException(SKILL_SERVICE_UNAVAILABLE);
        }
        validateName(name);
        if (!service.deleteSkill(name)) {
            throw new DataFetchingException("The skill was not deleted: it does not exist, is not a skill, "
                    + "or the repository failed (see the server log)");
        }
        return Boolean.TRUE;
    }

    /** Client-safe refusal of an invalid skill key (never echoes the key back). */
    private static void validateName(final String name) {
        try {
            McpSkillService.validateSkillName(name);
        } catch (IllegalArgumentException e) {
            throw new DataFetchingException("Invalid skill name: use letters, digits, '_' and '-' in segments "
                    + "separated by single '/', without leading or trailing slash");
        }
    }
}
