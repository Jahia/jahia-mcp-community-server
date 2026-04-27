package org.jahia.community.mcp.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.community.mcp.McpSkillService;
import org.jahia.community.mcp.config.McpConfigService;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;
import org.jahia.osgi.BundleUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@GraphQLTypeExtension(DXGraphQLProvider.Query.class)
@GraphQLName("McpQueries")
@GraphQLDescription("Jahia MCP community server queries")
public class McpQueryExtension {

    private McpQueryExtension() {
    }

    @GraphQLField
    @GraphQLName("mcpSettings")
    @GraphQLDescription("Returns the current MCP operation whitelist configuration")
    @GraphQLRequiresPermission("admin")
    public static GqlMcpSettings getSettings() {
        final McpConfigService config = BundleUtils.getOsgiService(McpConfigService.class, null);
        if (config == null) {
            return new GqlMcpSettings(new ArrayList<>());
        }
        return new GqlMcpSettings(new ArrayList<>(config.getWhitelist()));
    }

    @GraphQLField
    @GraphQLName("mcpSkills")
    @GraphQLDescription("Returns all MCP skills stored in JCR")
    @GraphQLRequiresPermission("admin")
    public static List<GqlMcpSkill> getSkills() {
        final McpSkillService service = BundleUtils.getOsgiService(McpSkillService.class, null);
        if (service == null) {
            return new ArrayList<>();
        }
        return service.listSkills().stream()
                .map(e -> new GqlMcpSkill(e.name, e.mcpName, e.description, e.content))
                .collect(Collectors.toList());
    }

    @GraphQLName("McpSettings")
    @GraphQLDescription("MCP operation access control settings")
    public static class GqlMcpSettings {

        private final List<String> whitelist;

        public GqlMcpSettings(List<String> whitelist) {
            this.whitelist = whitelist;
        }

        @GraphQLField
        @GraphQLName("whitelist")
        @GraphQLDescription("If non-empty, only operations in this list are allowed; empty means allow all")
        public List<String> getWhitelist() {
            return whitelist;
        }
    }

    @GraphQLName("McpSkill")
    @GraphQLDescription("A named skill providing instructions to an MCP client such as Claude Code")
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
        @GraphQLDescription("Unique skill identifier — the JCR node name, used as the key passed to getSkill")
        public String getName() {
            return name;
        }

        @GraphQLField
        @GraphQLName("mcpName")
        @GraphQLDescription("Human-readable display name for the skill (mcp:name property)")
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
        @GraphQLDescription("Full Markdown content of the skill — instructions the AI should follow")
        public String getContent() {
            return content;
        }
    }
}
