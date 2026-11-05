import {GET_MUTATION_FIELDS, GET_QUERY_FIELDS, GET_TYPE_FIELDS} from '../CommunityMcpConfig.gql';

// UX-6: a field of type [X!]! is NON_NULL -> LIST -> NON_NULL -> X. The named type is only
// reached if the documents unwrap at least four levels of ofType.
const ofTypeDepth = doc => (String(doc).match(/ofType/g) || []).length;

describe('field type documents', () => {
    test.each([
        ['Query roots', GET_QUERY_FIELDS],
        ['Mutation roots', GET_MUTATION_FIELDS],
        ['type fields', GET_TYPE_FIELDS]
    ])('%s unwrap [X!]! down to the named type', (_label, doc) => {
        expect(ofTypeDepth(doc)).toBeGreaterThanOrEqual(4);
    });
});
