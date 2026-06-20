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
    @GraphQLName("mcp")
    @GraphQLDescription("MCP query namespace")
    public static McpQuery mcp() {
        return new McpQuery();
    }
}
