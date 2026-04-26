package org.jahia.community.mcp.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;
import org.jahia.modules.graphql.provider.dxm.security.GraphQLRequiresPermission;
import org.jahia.osgi.BundleUtils;
import org.osgi.service.cm.Configuration;
import org.osgi.service.cm.ConfigurationAdmin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.List;

@GraphQLTypeExtension(DXGraphQLProvider.Mutation.class)
@GraphQLName("McpMutations")
@GraphQLDescription("Jahia MCP community server mutations")
public class McpMutationExtension {

    private static final Logger LOGGER = LoggerFactory.getLogger(McpMutationExtension.class);

    private McpMutationExtension() {
    }

    @GraphQLField
    @GraphQLName("mcpSaveSettings")
    @GraphQLDescription("Saves the MCP operation whitelist and blacklist configuration")
    @GraphQLRequiresPermission("admin")
    public static Boolean saveSettings(
            @GraphQLName("whitelist") @GraphQLNonNull @GraphQLDescription("Operations to allow; empty list means allow all") List<String> whitelist,
            @GraphQLName("blacklist") @GraphQLNonNull @GraphQLDescription("Operations to block; empty list means block none") List<String> blacklist) {
        try {
            final ConfigurationAdmin configAdmin = BundleUtils.getOsgiService(ConfigurationAdmin.class, null);
            if (configAdmin == null) {
                LOGGER.error("ConfigurationAdmin service is not available");
                return Boolean.FALSE;
            }
            final Configuration config = configAdmin.getConfiguration("org.jahia.community.mcp", null);
            Dictionary<String, Object> props = config.getProperties();
            if (props == null) {
                props = new Hashtable<>();
            }
            props.put("whitelist", String.join(",", whitelist));
            props.put("blacklist", String.join(",", blacklist));
            config.update(props);
            return Boolean.TRUE;
        } catch (IOException e) {
            LOGGER.error("Error saving MCP settings to OSGi configuration", e);
            return Boolean.FALSE;
        }
    }
}
