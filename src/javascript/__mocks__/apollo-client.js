// @apollo/client mock for unit tests.
// Tests set window.__apolloMock to drive query/mutation results.
const getMock = () => (typeof window !== 'undefined' && window.__apolloMock) || {};

export const gql = (literals, ...placeholders) =>
    literals.reduce((acc, lit, i) => acc + lit + (placeholders[i] || ''), '');

export const useQuery = (_query, _options) => {
    const mock = getMock();
    return mock.useQuery ? mock.useQuery(_query, _options) : {loading: false, data: undefined};
};

export const useMutation = (_mutation, _options) => {
    const mock = getMock();
    return mock.useMutation ? mock.useMutation(_mutation, _options) : [() => Promise.resolve({data: {}}), {loading: false}];
};

export const useApolloClient = () => {
    const mock = getMock();
    return mock.apolloClient || {query: () => Promise.resolve({data: {}})};
};
