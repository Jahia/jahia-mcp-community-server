package org.jahia.community.mcp.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;

@GraphQLTypeExtension(DXGraphQLProvider.Query.class)
@GraphQLDescription("MCP queries")
public class McpQueryExtension {

    private McpQueryExtension() {
    }

    @GraphQLField
    @GraphQLName("communityMcp")
    @GraphQLDescription("Community MCP query namespace (whitelist settings, skills)")
    public static McpQuery communityMcp() {
        return new McpQuery();
    }
}
