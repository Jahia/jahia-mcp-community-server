package org.jahia.community.mcp.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.community.mcp.config.McpConfigService;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;
import org.jahia.osgi.BundleUtils;

import java.util.ArrayList;
import java.util.List;

@GraphQLTypeExtension(DXGraphQLProvider.Query.class)
@GraphQLName("McpQueries")
@GraphQLDescription("Jahia MCP community server queries")
public class McpQueryExtension {

    private McpQueryExtension() {
    }

    @GraphQLField
    @GraphQLName("mcpSettings")
    @GraphQLDescription("Returns the current MCP operation whitelist and blacklist configuration")
    @GraphQLRequiresPermission("admin")
    public static GqlMcpSettings getSettings() {
        final McpConfigService config = BundleUtils.getOsgiService(McpConfigService.class, null);
        if (config == null) {
            return new GqlMcpSettings(new ArrayList<>(), new ArrayList<>());
        }
        return new GqlMcpSettings(
                new ArrayList<>(config.getWhitelist()),
                new ArrayList<>(config.getBlacklist())
        );
    }

    @GraphQLName("McpSettings")
    @GraphQLDescription("MCP operation access control settings")
    public static class GqlMcpSettings {

        private final List<String> whitelist;
        private final List<String> blacklist;

        public GqlMcpSettings(List<String> whitelist, List<String> blacklist) {
            this.whitelist = whitelist;
            this.blacklist = blacklist;
        }

        @GraphQLField
        @GraphQLName("whitelist")
        @GraphQLDescription("If non-empty, only operations in this list are allowed; empty means allow all")
        public List<String> getWhitelist() {
            return whitelist;
        }

        @GraphQLField
        @GraphQLName("blacklist")
        @GraphQLDescription("Operations in this list are always blocked; empty means block none")
        public List<String> getBlacklist() {
            return blacklist;
        }
    }
}
