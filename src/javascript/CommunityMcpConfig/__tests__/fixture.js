import {fireEvent, screen, within} from '@testing-library/react';

// Shared fixture: drives the __mocks__ Apollo layer and finds the admin editor's parts.
// Not a test file (testMatch only picks *.test.js[x]).

const QUERY_FIELDS = [
    {name: 'admin', description: 'Admin queries', type: {name: 'AdminQuery', kind: 'OBJECT'}},
    {name: 'currentUser', description: '', type: {name: 'User', kind: 'OBJECT'}}
];
const MUTATION_FIELDS = [
    {name: 'admin', description: '', type: {name: 'AdminMutation', kind: 'OBJECT'}},
    {name: 'communityMcp', description: '', type: {name: 'CommunityMcpMutation', kind: 'OBJECT'}}
];
export const ADMIN_TYPE_FIELDS = [
    {name: 'jahia', description: '', type: {name: 'JahiaQuery', kind: 'OBJECT'}},
    {name: 'version', description: '', type: {name: 'String', kind: 'SCALAR'}}
];

const ok = {data: {communityMcp: {saveSettings: true}}};

export function installApolloMock({whitelist = [], clustered = false, saveSpy, querySpy, errors = {}, loading = false, refetch} = {}) {
    // Stable result objects: the real @apollo/client returns referentially-stable results,
    // and the component's useEffect([settingsData]) would loop forever on fresh objects.
    const refetchSpy = refetch || jest.fn();
    const make = (data, error) => ({
        loading,
        error,
        data: error || loading ? undefined : data,
        refetch: refetchSpy
    });
    const results = {
        queryFields: make({queryFields: {fields: QUERY_FIELDS}}, errors.queryFields),
        mutationFields: make({mutationFields: {fields: MUTATION_FIELDS}}, errors.mutationFields),
        settings: make({communityMcp: {settings: {whitelist, clustered}}}, errors.settings)
    };

    const useQuery = query => {
        const q = String(query);
        if (q.includes('queryFields')) {
            return results.queryFields;
        }

        if (q.includes('mutationFields')) {
            return results.mutationFields;
        }

        return results.settings;
    };

    const save = saveSpy || jest.fn().mockResolvedValue(ok);
    const useMutation = () => [save, {loading: false}];
    const apolloClient = {
        query: querySpy || jest.fn().mockResolvedValue({data: {typeFields: {fields: ADMIN_TYPE_FIELDS}}})
    };

    window.__apolloMock = {useQuery, useMutation, apolloClient};
    return {save, apolloClient, refetch: refetchSpy};
}

export const queriesTree = () => screen.getByRole('tree', {name: 'label.queriesHeading'});
export const mutationsTree = () => screen.getByRole('tree', {name: 'label.mutationsHeading'});
export const item = (tree, name) => within(tree).getByRole('treeitem', {name});
export const saveButton = () => screen.getByRole('button', {name: 'label.save'});
export const liveRegion = () => document.getElementById('mcp-live');
export const panelHeading = () => screen.getByRole('heading', {name: 'label.allowList'});
export const problemsRegion = () => screen.getByRole('region', {name: 'label.problemEntriesTitle'});
export const policyText = key => screen.getByText(key, {selector: 'p'});
export const confirmEmptying = () => fireEvent.click(
    within(screen.getByRole('alertdialog', {name: 'label.confirmClearTitle'})).getByRole('button', {name: 'label.confirmClearAction'}));
