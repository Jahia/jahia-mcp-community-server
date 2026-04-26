import {gql} from '@apollo/client';

export const GET_SETTINGS = gql`
    query McpGetSettings {
        mcpSettings {
            whitelist
            blacklist
        }
    }
`;

export const SAVE_SETTINGS = gql`
    mutation McpSaveSettings($whitelist: [String]!, $blacklist: [String]!) {
        mcpSaveSettings(whitelist: $whitelist, blacklist: $blacklist)
    }
`;

export const GET_QUERY_FIELDS = gql`
    query McpGetQueryFields {
        queryFields: __type(name: "Query") {
            fields(includeDeprecated: false) {
                name
                description
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
            }
        }
    }
`;
