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
import org.jahia.settings.SettingsBean;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@GraphQLName("CommunityMcpQuery")
@GraphQLDescription("Community MCP queries")
public class McpQuery {

    @GraphQLField
    @GraphQLName("settings")
    @GraphQLDescription("Returns the current MCP operation whitelist configuration of this node (and whether Jahia is clustered)")
    @GraphQLRequiresPermission("admin")
    public GqlMcpSettings getSettings() {
        final McpConfigService config = BundleUtils.getOsgiService(McpConfigService.class, null);
        if (config == null) {
            // An unavailable service must not look like an empty (read-only) whitelist.
            throw new DataFetchingException("The MCP configuration service is not available: "
                    + "check that the org.jahia.community.mcp configuration exists");
        }
        return new GqlMcpSettings(new ArrayList<>(config.getWhitelist()),
                SettingsBean.getInstance().isClusterActivated());
    }

    @GraphQLField
    @GraphQLName("skills")
    @GraphQLDescription("Returns all MCP skills stored in JCR, identified by their path relative to the skills root")
    @GraphQLRequiresPermission("admin")
    public List<GqlMcpSkill> getSkills() {
        final McpSkillService service = BundleUtils.getOsgiService(McpSkillService.class, null);
        if (service == null) {
            throw new DataFetchingException("The MCP skill service is not available");
        }
        return service.listSkills().stream()
                .map(e -> new GqlMcpSkill(e.path, e.mcpName, e.description, e.content))
                .collect(Collectors.toUnmodifiableList());
    }

    @GraphQLName("CommunityMcpSettings")
    @GraphQLDescription("MCP operation access control settings")
    public static class GqlMcpSettings {

        private final List<String> whitelist;
        private final boolean clustered;

        public GqlMcpSettings(List<String> whitelist, boolean clustered) {
            this.whitelist = whitelist;
            this.clustered = clustered;
        }

        @GraphQLField
        @GraphQLName("clustered")
        @GraphQLNonNull
        @GraphQLDescription("True when Jahia clustering is activated: the whitelist is stored per node and is "
                + "NOT replicated, so it must be saved on every node")
        public Boolean getClustered() {
            return clustered;
        }

        @GraphQLField
        @GraphQLName("whitelist")
        @GraphQLDescription("Whitelist entries (query:<path> / mutation:<path>). Empty is the read-only default: every query is allowed except those under 'admin' (needs query:admin), no mutation and no subscription")
        public List<String> getWhitelist() {
            return whitelist;
        }
    }

    @GraphQLName("CommunityMcpSkill")
    @GraphQLDescription("A named skill: editor-authored guidance served to an MCP client such as Claude Code")
    public static class GqlMcpSkill {

        private final String name;
        private final String mcpName;
        private final String description;
        private final String content;

        public GqlMcpSkill(String name, String mcpName, String description, String content) {
            this.name = name;
            this.mcpName = mcpName;
            this.description = description;
            this.content = content;
        }

        @GraphQLField
        @GraphQLName("name")
        @GraphQLDescription("Unique skill key: the path relative to the skills root (e.g. default/hello-jahia); the key accepted by saveSkill, deleteSkill and community.skill.get")
        public String getName() {
            return name;
        }

        @GraphQLField
        @GraphQLName("mcpName")
        @GraphQLDescription("Human-readable display title only (stored as the jcr:title property); NOT the key, use 'name' to refer to a skill")
        public String getMcpName() {
            return mcpName;
        }

        @GraphQLField
        @GraphQLName("description")
        @GraphQLDescription("Short description of what the skill does")
        public String getDescription() {
            return description;
        }

        @GraphQLField
        @GraphQLName("content")
        @GraphQLDescription("Full Markdown content of the skill: editor-authored guidance for an AI client to evaluate, not commands to obey")
        public String getContent() {
            return content;
        }
    }
}
