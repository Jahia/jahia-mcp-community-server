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
const MUTATION_FIELDS = [
    {name: 'admin', description: '', type: {name: 'AdminMutation', kind: 'OBJECT'}}
];
const ADMIN_TYPE_FIELDS = [
    {name: 'jahia', description: '', type: {name: 'JahiaQuery', kind: 'OBJECT'}}
];

function installApolloMock({whitelist = [], saveSpy, querySpy} = {}) {
    // Stable result objects: the real @apollo/client returns referentially-stable results,
    // and the component's useEffect([settingsData]) would loop forever on fresh objects.
    const queryResult = {loading: false, data: {queryFields: {fields: QUERY_FIELDS}}};
    const mutationResult = {loading: false, data: {mutationFields: {fields: MUTATION_FIELDS}}};
    const settingsResult = {loading: false, data: {communityMcp: {settings: {whitelist}}}};

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
        saveSpy || jest.fn().mockResolvedValue({data: {communityMcp: {saveSettings: true}}}),
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
    test('renders query and mutation root operations as distinct entries', () => {
        installApolloMock({whitelist: []});
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.getByRole('checkbox', {name: 'query:admin'})).toBeInTheDocument();
        expect(screen.getByRole('checkbox', {name: 'query:currentUser'})).toBeInTheDocument();
        expect(screen.getByRole('checkbox', {name: 'mutation:admin'})).toBeInTheDocument();
    });

    test('allowing the query does not allow the same-named mutation', () => {
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.getByRole('checkbox', {name: 'query:admin'})).toBeChecked();
        expect(screen.getByRole('checkbox', {name: 'mutation:admin'})).not.toBeChecked();
    });

    test('a legacy unprefixed entry loads as query-only and can be saved in the new form', async () => {
        const saveSpy = jest.fn().mockResolvedValue({data: {communityMcp: {saveSettings: true}}});
        installApolloMock({whitelist: ['admin'], saveSpy});
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.getByRole('checkbox', {name: 'query:admin'})).toBeChecked();
        expect(screen.getByRole('checkbox', {name: 'mutation:admin'})).not.toBeChecked();
        // The migrated form differs from what the server holds, so Save is enabled straight away.
        fireEvent.click(screen.getByRole('button', {name: 'label.save'}));

        await waitFor(() => expect(saveSpy).toHaveBeenCalledTimes(1));
        expect(saveSpy).toHaveBeenCalledWith({variables: {whitelist: ['query:admin']}});
    });

    test('Save calls mcpSaveSettings with the merged whitelist array', async () => {
        const saveSpy = jest.fn().mockResolvedValue({data: {communityMcp: {saveSettings: true}}});
        installApolloMock({whitelist: [], saveSpy});
        render(<CommunityMcpConfigAdmin/>);

        // Toggling a checkbox marks the form dirty and enables Save.
        // (The i18next mock echoes translation keys, so the Save button's accessible name
        // is the raw key 'label.save'.)
        fireEvent.click(screen.getByRole('checkbox', {name: 'query:currentUser'}));
        fireEvent.click(screen.getByRole('button', {name: 'label.save'}));

        await waitFor(() => expect(saveSpy).toHaveBeenCalledTimes(1));
        expect(saveSpy).toHaveBeenCalledWith({variables: {whitelist: ['query:currentUser']}});
    });

    test('expanding a whitelisted node renders its children as disabled (covered by ancestor)', async () => {
        const querySpy = jest.fn().mockResolvedValue({data: {typeFields: {fields: ADMIN_TYPE_FIELDS}}});
        installApolloMock({whitelist: ['query:admin'], querySpy});
        render(<CommunityMcpConfigAdmin/>);

        // 'query:admin' is whitelisted → its checkbox is checked.
        expect(screen.getByRole('checkbox', {name: 'query:admin'})).toBeChecked();

        // Expand 'query:admin' (fires the lazy GET_TYPE_FIELDS query). Queries are listed first and
        // sorted alphabetically, so its expand button is the first (all expand buttons share the
        // echoed key 'label.expand' under the i18next mock).
        fireEvent.click(screen.getAllByRole('button', {name: 'label.expand'})[0]);
        await waitFor(() => expect(querySpy).toHaveBeenCalledTimes(1));

        // The child 'query:admin.jahia' is covered by the ancestor → checkbox disabled + checked.
        const child = await screen.findByRole('checkbox', {name: /query:admin\.jahia/});
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
