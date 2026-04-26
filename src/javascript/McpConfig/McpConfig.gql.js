import {gql} from '@apollo/client';

export const GET_SETTINGS = gql`
    query McpGetSettings {
        mcpSettings {
            whitelist
        }
    }
`;

export const SAVE_SETTINGS = gql`
    mutation McpSaveSettings($whitelist: [String]!) {
        mcpSaveSettings(whitelist: $whitelist)
    }
`;

export const GET_QUERY_FIELDS = gql`
    query McpGetQueryFields {
        queryFields: __type(name: "Query") {
            fields(includeDeprecated: false) {
                name
                description
                type { name kind ofType { name kind ofType { name kind } } }
            }
        }
    }
`;

export const GET_MUTATION_FIELDS = gql`
    query McpGetMutationFields {
        mutationFields: __type(name: "Mutation") {
            fields(includeDeprecated: false) {
                name
                description
                type { name kind ofType { name kind ofType { name kind } } }
            }
        }
    }
`;

export const GET_TYPE_FIELDS = gql`
    query McpGetTypeFields($typeName: String!) {
        typeFields: __type(name: $typeName) {
            fields(includeDeprecated: false) {
                name
                description
                type { name kind ofType { name kind ofType { name kind } } }
            }
        }
    }
`;
