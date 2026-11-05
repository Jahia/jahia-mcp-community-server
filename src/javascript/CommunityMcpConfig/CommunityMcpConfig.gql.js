import {gql} from '@apollo/client';

// Unwraps up to five NON_NULL / LIST wrappers: [X!]! is NON_NULL -> LIST -> NON_NULL -> X, so
// three levels left such fields without a named type and impossible to expand (UX-6). Inlined,
// not a fragment: the documents stay plain selections for Jahia's introspection guard.
const TYPE_SELECTION =
    'type { name kind ofType { name kind ofType { name kind ofType { name kind ofType { name kind ofType { name kind } } } } } }';

export const GET_SETTINGS = gql`
    query McpGetSettings {
        communityMcp {
            settings {
                whitelist
            }
        }
    }
`;

export const SAVE_SETTINGS = gql`
    mutation McpSaveSettings($whitelist: [String]!) {
        communityMcp {
            saveSettings(whitelist: $whitelist)
        }
    }
`;

export const GET_QUERY_FIELDS = gql`
    query McpGetQueryFields {
        queryFields: __type(name: "Query") {
            fields(includeDeprecated: false) {
                name
                description
                ${TYPE_SELECTION}
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
                ${TYPE_SELECTION}
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
                ${TYPE_SELECTION}
            }
        }
    }
`;
