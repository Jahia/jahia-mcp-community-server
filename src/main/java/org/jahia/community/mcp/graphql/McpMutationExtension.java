package org.jahia.community.mcp.graphql;

import graphql.annotations.annotationTypes.GraphQLDescription;
import graphql.annotations.annotationTypes.GraphQLField;
import graphql.annotations.annotationTypes.GraphQLName;
import graphql.annotations.annotationTypes.GraphQLTypeExtension;
import org.jahia.modules.graphql.provider.dxm.DXGraphQLProvider;

@GraphQLTypeExtension(DXGraphQLProvider.Mutation.class)
@GraphQLDescription("MCP mutations")
public class McpMutationExtension {

    private McpMutationExtension() {
    }

    @GraphQLField
    @GraphQLName("mcp")
    @GraphQLDescription("MCP mutation namespace")
    public static McpMutation mcp() {
        return new McpMutation();
    }
}
