package org.jahia.community.mcp.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLNonNull;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.community.mcp.McpSkillService;
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
    @GraphQLDescription("Saves the MCP operation whitelist configuration")
    @GraphQLRequiresPermission("admin")
    public static Boolean saveSettings(
            @GraphQLName("whitelist") @GraphQLNonNull @GraphQLDescription("Operations to allow; empty list means allow all") List<String> whitelist) {
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
            config.update(props);
            return Boolean.TRUE;
        } catch (IOException e) {
            LOGGER.error("Error saving MCP settings to OSGi configuration", e);
            return Boolean.FALSE;
        }
    }

    @GraphQLField
    @GraphQLName("mcpSaveSkill")
    @GraphQLDescription("Creates or updates a named MCP skill in JCR")
    @GraphQLRequiresPermission("admin")
    public static Boolean saveSkill(
            @GraphQLName("name") @GraphQLNonNull @GraphQLDescription("Unique skill identifier (becomes the JCR node name)") String name,
            @GraphQLName("description") @GraphQLDescription("Short description of what the skill does") String description,
            @GraphQLName("content") @GraphQLNonNull @GraphQLDescription("Full Markdown content — instructions the AI should follow") String content) {
        final McpSkillService service = BundleUtils.getOsgiService(McpSkillService.class, null);
        if (service == null) {
            LOGGER.error("McpSkillService is not available");
            return Boolean.FALSE;
        }
        return service.saveSkill(name, description, content);
    }

    @GraphQLField
    @GraphQLName("mcpDeleteSkill")
    @GraphQLDescription("Deletes a named MCP skill from JCR")
    @GraphQLRequiresPermission("admin")
    public static Boolean deleteSkill(
            @GraphQLName("name") @GraphQLNonNull @GraphQLDescription("Name of the skill to delete") String name) {
        final McpSkillService service = BundleUtils.getOsgiService(McpSkillService.class, null);
        if (service == null) {
            LOGGER.error("McpSkillService is not available");
            return Boolean.FALSE;
        }
        return service.deleteSkill(name);
    }
}
