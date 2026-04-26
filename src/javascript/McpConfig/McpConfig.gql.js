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

export const GET_OPERATIONS = gql`
    query McpGetOperations {
        queryFields: __type(name: "Query") {
            fields(includeDeprecated: false) {
                name
                description
            }
        }
        mutationFields: __type(name: "Mutation") {
            fields(includeDeprecated: false) {
                name
                description
            }
        }
    }
`;
