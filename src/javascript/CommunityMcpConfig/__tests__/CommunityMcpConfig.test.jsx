import React from 'react';
import {render, screen, fireEvent, waitFor} from '@testing-library/react';
import {CommunityMcpConfigAdmin} from '../CommunityMcpConfig';

// F11 (component) — mounts the real admin editor against the __mocks__ Apollo/moonstone/i18next
// layer. Drives the whitelist round-trip: render → expand → toggle → Save, asserting the
// mutation is called with the merged array and that ancestor-covered children are disabled.

const QUERY_FIELDS = [
    {name: 'admin', description: '', type: {name: 'AdminQuery', kind: 'OBJECT'}},
    {name: 'currentUser', description: '', type: {name: 'User', kind: 'OBJECT'}}
];
const MUTATION_FIELDS = [];
const ADMIN_TYPE_FIELDS = [
    {name: 'jahia', description: '', type: {name: 'JahiaQuery', kind: 'OBJECT'}}
];

function installApolloMock({whitelist = [], saveSpy, querySpy} = {}) {
    // Stable result objects: the real @apollo/client returns referentially-stable results,
    // and the component's useEffect([settingsData]) would loop forever on fresh objects.
    const queryResult = {loading: false, data: {queryFields: {fields: QUERY_FIELDS}}};
    const mutationResult = {loading: false, data: {mutationFields: {fields: MUTATION_FIELDS}}};
    const settingsResult = {loading: false, data: {mcp: {settings: {whitelist}}}};

    const useQuery = query => {
        const q = String(query);
        if (q.includes('queryFields')) {
            return queryResult;
        }

        if (q.includes('mutationFields')) {
            return mutationResult;
        }

        // GET_SETTINGS
        return settingsResult;
    };

    const useMutation = () => [
        saveSpy || jest.fn().mockResolvedValue({data: {mcp: {saveSettings: true}}}),
        {loading: false, error: undefined}
    ];

    const apolloClient = {
        query: querySpy || jest.fn().mockResolvedValue({data: {typeFields: {fields: ADMIN_TYPE_FIELDS}}})
    };

    window.__apolloMock = {useQuery, useMutation, apolloClient};
}

afterEach(() => {
    delete window.__apolloMock;
});

describe('CommunityMcpConfig admin component', () => {
    test('renders the root query operations after data loads', () => {
        installApolloMock({whitelist: []});
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.getByRole('checkbox', {name: 'admin'})).toBeInTheDocument();
        expect(screen.getByRole('checkbox', {name: 'currentUser'})).toBeInTheDocument();
    });

    test('Save calls mcpSaveSettings with the merged whitelist array', async () => {
        const saveSpy = jest.fn().mockResolvedValue({data: {mcp: {saveSettings: true}}});
        installApolloMock({whitelist: [], saveSpy});
        render(<CommunityMcpConfigAdmin/>);

        // Toggling a checkbox marks the form dirty and enables Save.
        // (The i18next mock echoes translation keys, so the Save button's accessible name
        // is the raw key 'label.save'.)
        fireEvent.click(screen.getByRole('checkbox', {name: 'currentUser'}));
        fireEvent.click(screen.getByRole('button', {name: 'label.save'}));

        await waitFor(() => expect(saveSpy).toHaveBeenCalledTimes(1));
        expect(saveSpy).toHaveBeenCalledWith({variables: {whitelist: ['currentUser']}});
    });

    test('expanding a whitelisted node renders its children as disabled (covered by ancestor)', async () => {
        const querySpy = jest.fn().mockResolvedValue({data: {typeFields: {fields: ADMIN_TYPE_FIELDS}}});
        installApolloMock({whitelist: ['admin'], querySpy});
        render(<CommunityMcpConfigAdmin/>);

        // 'admin' is whitelisted → its checkbox is checked.
        expect(screen.getByRole('checkbox', {name: 'admin'})).toBeChecked();

        // Expand 'admin' (fires the lazy GET_TYPE_FIELDS query). Operations are sorted
        // alphabetically, so admin's expand button is the first (both expand buttons share
        // the echoed key 'label.expand' under the i18next mock).
        fireEvent.click(screen.getAllByRole('button', {name: 'label.expand'})[0]);
        await waitFor(() => expect(querySpy).toHaveBeenCalledTimes(1));

        // The child 'admin.jahia' is covered by the ancestor 'admin' → checkbox disabled + checked.
        const child = await screen.findByRole('checkbox', {name: /admin\.jahia/});
        expect(child).toBeDisabled();
        expect(child).toBeChecked();
    });

    test('shows a loader while any query is still loading', () => {
        window.__apolloMock = {
            useQuery: () => ({loading: true, data: undefined}),
            useMutation: () => [jest.fn(), {loading: false}],
            apolloClient: {query: jest.fn()}
        };
        render(<CommunityMcpConfigAdmin/>);
        expect(screen.getByRole('status')).toBeInTheDocument();
    });
});
