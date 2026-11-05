import React from 'react';
import {act, fireEvent, render, screen, waitFor, within} from '@testing-library/react';
import {CommunityMcpConfigAdmin} from '../CommunityMcpConfig';

// Mounts the real admin editor against the __mocks__ Apollo/moonstone/i18next layer
// (the i18next mock echoes translation keys, so visible strings are the raw keys).

import {
    ADMIN_TYPE_FIELDS,
    confirmEmptying,
    installApolloMock,
    item,
    liveRegion,
    mutationsTree,
    panelHeading,
    policyText,
    problemsRegion,
    queriesTree,
    saveButton
} from './fixture';

afterEach(() => {
    delete window.__apolloMock;
    jest.useRealTimers();
    // Spies such as console.error silencing must not leak into later tests.
    jest.restoreAllMocks();
});

describe('tree rendering and selection', () => {
    test('renders query and mutation roots in two trees, named by their visible name', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-level', '1');
        expect(item(queriesTree(), 'currentUser')).toBeInTheDocument();
        expect(item(mutationsTree(), 'admin')).toBeInTheDocument();
        // Descriptions are visible text and announced as the item description.
        expect(item(queriesTree(), 'admin')).toHaveAccessibleDescription('label.adminQueryNode Admin queries');
    });

    test('allowing the query does not allow the same-named mutation', () => {
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'true');
        expect(item(mutationsTree(), 'admin')).toHaveAttribute('aria-checked', 'false');
        expect(screen.getByText('label.policyRestricted')).toBeInTheDocument();
    });

    test('clicking a row toggles it and Save sends the allow list', async () => {
        const {save} = installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(within(item(queriesTree(), 'currentUser')).getByText('currentUser'));
        expect(item(queriesTree(), 'currentUser')).toHaveAttribute('aria-checked', 'true');
        expect(screen.getByText('label.unsavedChanges')).toBeInTheDocument();
        fireEvent.click(saveButton());

        await waitFor(() => expect(save).toHaveBeenCalledWith({variables: {whitelist: ['query:currentUser']}}));
        expect(await screen.findByText('label.saveSuccess')).toBeInTheDocument();
        expect(screen.queryByText('label.unsavedChanges')).not.toBeInTheDocument();
    });

    test('a legacy unprefixed entry loads as query-only, explains the migration and saves the new form', async () => {
        const {save} = installApolloMock({whitelist: ['admin']});
        render(<CommunityMcpConfigAdmin/>);

        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'true');
        expect(item(mutationsTree(), 'admin')).toHaveAttribute('aria-checked', 'false');
        expect(screen.getByText('label.migrated')).toBeInTheDocument();
        fireEvent.click(saveButton());

        await waitFor(() => expect(save).toHaveBeenCalledWith({variables: {whitelist: ['query:admin']}}));
    });

    test('an empty allow list states the read-only default', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.getByText('label.policyReadOnly')).toBeInTheDocument();
        expect(screen.getByText('label.queriesNoteDefault')).toBeInTheDocument();
    });

    test('a list with mutations but no query warns that every query is refused', () => {
        installApolloMock({whitelist: ['mutation:admin']});
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.getByText('label.policyNoQuery')).toBeInTheDocument();
    });

    test('a parent with an allowed descendant is shown as partially checked', () => {
        installApolloMock({whitelist: ['query:admin.jahia']});
        render(<CommunityMcpConfigAdmin/>);

        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'mixed');
        expect(screen.getAllByText('label.allowedCount')).toHaveLength(2);
    });

    test('children of an allowed parent are checked, explain why, and cannot be toggled', async () => {
        const {apolloClient} = installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(within(item(queriesTree(), 'admin')).getByTestId('mcp-toggle'));
        await waitFor(() => expect(apolloClient.query).toHaveBeenCalledTimes(1));

        const child = await within(queriesTree()).findByRole('treeitem', {name: 'jahia'});
        expect(child).toHaveAttribute('aria-checked', 'true');
        expect(child).toHaveAttribute('aria-level', '2');
        expect(child).toHaveAccessibleDescription('label.coveredBy');
        fireEvent.click(within(child).getByText('jahia'));
        expect(child).toHaveAttribute('aria-checked', 'true');
        expect(screen.queryByText('label.unsavedChanges')).not.toBeInTheDocument();
    });

    test('the communityMcp mutation is shown as locked and cannot be allowed', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        const locked = item(mutationsTree(), 'communityMcp');
        expect(locked).toHaveAccessibleDescription('label.lockedNode');
        expect(locked).not.toHaveAttribute('aria-expanded');
        fireEvent.keyDown(locked, {key: ' '});
        expect(locked).toHaveAttribute('aria-checked', 'false');
        // Not merely shown unchecked: nothing was added to the list, and the reason is announced.
        expect(screen.queryByText('label.unsavedChanges')).not.toBeInTheDocument();
        expect(liveRegion()).toHaveTextContent('label.lockedNode');
        expect(screen.queryByText('mutation:communityMcp')).not.toBeInTheDocument();
        fireEvent.click(saveButton());
        expect(screen.getByText('label.noChanges')).toBeInTheDocument();
    });

    test('the Jahia configuration mutation is locked like communityMcp, with its own reason', async () => {
        const typeFields = {
            AdminMutation: [{name: 'jahia', description: '', type: {name: 'JahiaMutation', kind: 'OBJECT'}}],
            JahiaMutation: [{name: 'configuration', description: '', type: {name: 'ConfigMutation', kind: 'OBJECT'}}]
        };
        const querySpy = jest.fn(({variables}) => Promise.resolve({data: {typeFields: {fields: typeFields[variables.typeName] || []}}}));
        installApolloMock({whitelist: ['mutation:admin'], querySpy});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(mutationsTree(), 'admin'), {key: 'ArrowRight'});
        fireEvent.keyDown(await within(mutationsTree()).findByRole('treeitem', {name: 'jahia'}), {key: 'ArrowRight'});
        const config = await within(mutationsTree()).findByRole('treeitem', {name: 'configuration'});
        expect(config).toHaveAccessibleDescription('label.lockedConfigurationNode');
        expect(config).toHaveAttribute('aria-checked', 'false');
        expect(config).not.toHaveAttribute('aria-expanded');
        fireEvent.keyDown(config, {key: ' '});
        expect(screen.queryByText('label.unsavedChanges')).not.toBeInTheDocument();
        expect(liveRegion()).toHaveTextContent('label.lockedConfigurationNode');
    });
});

describe('loading and load errors', () => {
    test('shows visible loading text while any query is loading', () => {
        installApolloMock({loading: true});
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.getByText('label.loading')).toBeInTheDocument();
        expect(screen.getByRole('status')).toBeInTheDocument();
    });

    test('a settings load error is shown as an alert, offers retry and never offers Save', () => {
        const {refetch} = installApolloMock({errors: {settings: new Error('denied')}});
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.getByRole('alert')).toHaveTextContent('label.loadError');
        expect(screen.queryByText('label.policyReadOnly')).not.toBeInTheDocument();
        expect(screen.queryByRole('button', {name: 'label.save'})).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', {name: 'label.retry'}));
        expect(refetch).toHaveBeenCalled();
    });

    test('a successful retry moves focus to the panel heading, never to the page body', async () => {
        const refetch = jest.fn().mockResolvedValue({});
        installApolloMock({errors: {settings: new Error('denied')}, refetch});
        const {rerender} = render(<CommunityMcpConfigAdmin/>);

        const retry = screen.getByRole('button', {name: 'label.retry'});
        act(() => retry.focus());
        fireEvent.click(retry);
        // Apollo shows the loading state, which removes "Try again"...
        installApolloMock({loading: true});
        rerender(<CommunityMcpConfigAdmin/>);
        expect(screen.queryByRole('button', {name: 'label.retry'})).not.toBeInTheDocument();
        // ...then the data.
        installApolloMock({whitelist: ['query:admin']});
        rerender(<CommunityMcpConfigAdmin/>);

        await waitFor(() => expect(panelHeading()).toHaveFocus());
    });

    test('a retry that fails again after a loading state puts focus back on Try again', async () => {
        installApolloMock({errors: {settings: new Error('denied')}, refetch: jest.fn().mockResolvedValue({})});
        const {rerender} = render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(screen.getByRole('button', {name: 'label.retry'}));
        installApolloMock({loading: true});
        rerender(<CommunityMcpConfigAdmin/>);
        installApolloMock({errors: {settings: new Error('still denied')}});
        rerender(<CommunityMcpConfigAdmin/>);

        await waitFor(() => expect(screen.getByRole('button', {name: 'label.retry'})).toHaveFocus());
    });

    test('a retry that fails again keeps focus on Try again and raises no unhandled rejection', async () => {
        const refetch = jest.fn().mockRejectedValue(new Error('still down'));
        const logged = jest.spyOn(console, 'error').mockImplementation(() => {});
        installApolloMock({errors: {settings: new Error('denied')}, refetch});
        render(<CommunityMcpConfigAdmin/>);

        const retry = screen.getByRole('button', {name: 'label.retry'});
        act(() => retry.focus());
        fireEvent.click(retry);
        await act(async () => {
            await Promise.resolve();
        });

        expect(refetch).toHaveBeenCalled();
        expect(logged).toHaveBeenCalledWith('Failed to reload MCP settings:', expect.any(Error));
        expect(screen.getByRole('button', {name: 'label.retry'})).toHaveFocus();
    });

    test('settings delivered again while edits are pending keep the edits and say the server list changed', async () => {
        installApolloMock({whitelist: ['query:admin']});
        const {rerender} = render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        installApolloMock({whitelist: ['mutation:admin']});
        rerender(<CommunityMcpConfigAdmin/>);

        expect(item(queriesTree(), 'currentUser')).toHaveAttribute('aria-checked', 'true');
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'true');
        expect(screen.getByText('label.unsavedChanges')).toBeInTheDocument();
        expect(await screen.findByText('label.serverChanged')).toBeInTheDocument();

        // Discard now restores the list the server holds today.
        fireEvent.click(screen.getByRole('button', {name: 'label.discard'}));
        expect(item(mutationsTree(), 'admin')).toHaveAttribute('aria-checked', 'true');
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'false');
    });

    test('settings delivered again with no pending edit replace the list', () => {
        installApolloMock({whitelist: ['query:admin']});
        const {rerender} = render(<CommunityMcpConfigAdmin/>);

        installApolloMock({whitelist: ['query:currentUser']});
        rerender(<CommunityMcpConfigAdmin/>);

        expect(item(queriesTree(), 'currentUser')).toHaveAttribute('aria-checked', 'true');
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'false');
        expect(screen.queryByText('label.unsavedChanges')).not.toBeInTheDocument();
        expect(screen.queryByText('label.serverChanged')).not.toBeInTheDocument();
    });

    test('a schema load error is shown as an alert', () => {
        installApolloMock({errors: {mutationFields: new Error('boom')}});
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.getByRole('alert')).toHaveTextContent('label.schemaLoadError');
        expect(screen.queryByRole('tree')).not.toBeInTheDocument();
    });

    test('a failed expansion shows an error and expanding again retries', async () => {
        const querySpy = jest.fn()
            .mockRejectedValueOnce(new Error('network'))
            .mockResolvedValueOnce({data: {typeFields: {fields: ADMIN_TYPE_FIELDS}}});
        jest.spyOn(console, 'error').mockImplementation(() => {});
        installApolloMock({querySpy});
        render(<CommunityMcpConfigAdmin/>);

        const admin = item(queriesTree(), 'admin');
        fireEvent.keyDown(admin, {key: 'ArrowRight'});
        expect(await screen.findByRole('alert')).toHaveTextContent('label.loadFieldsError');
        expect(admin).toHaveAttribute('aria-expanded', 'false');

        fireEvent.keyDown(admin, {key: 'ArrowRight'});
        expect(await within(queriesTree()).findByRole('treeitem', {name: 'jahia'})).toBeInTheDocument();
        expect(querySpy).toHaveBeenCalledTimes(2);
        expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });
});

describe('save feedback', () => {
    test('a save returning false shows a persistent, dismissible error', async () => {
        jest.useFakeTimers();
        installApolloMock({saveSpy: jest.fn().mockResolvedValue({data: {communityMcp: {saveSettings: false}}})});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        await act(async () => {
            fireEvent.click(saveButton());
        });
        expect(screen.getByRole('alert')).toHaveTextContent('label.saveError');
        act(() => {
            jest.advanceTimersByTime(60000);
        });
        expect(screen.getByRole('alert')).toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', {name: 'label.dismiss'}));
        expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });

    test('a GraphQL error shows the server message in English', async () => {
        const err = Object.assign(new Error('GraphQL error'), {graphQLErrors: [{message: 'Invalid entry foo'}]});
        installApolloMock({saveSpy: jest.fn().mockRejectedValue(err)});
        jest.spyOn(console, 'error').mockImplementation(() => {});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: 'Enter'});
        fireEvent.click(saveButton());

        const alert = await screen.findByRole('alert');
        expect(alert).toHaveTextContent('label.saveErrorServer');
        expect(within(alert).getByText('Invalid entry foo')).toHaveAttribute('lang', 'en');
    });

    test('an error without GraphQL details falls back to its message', async () => {
        installApolloMock({saveSpy: jest.fn().mockRejectedValue(new Error('Forbidden'))});
        jest.spyOn(console, 'error').mockImplementation(() => {});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        fireEvent.click(saveButton());

        expect(await screen.findByText('Forbidden')).toBeInTheDocument();
    });

    test('a network failure shows the network message', async () => {
        const err = Object.assign(new Error('Failed to fetch'), {networkError: new Error('offline')});
        installApolloMock({saveSpy: jest.fn().mockRejectedValue(err)});
        jest.spyOn(console, 'error').mockImplementation(() => {});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        fireEvent.click(saveButton());

        expect(await screen.findByRole('alert')).toHaveTextContent('label.saveNetworkError');
    });

    test('Save stays enabled and focused with no changes, and says so', () => {
        const {save} = installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        act(() => saveButton().focus());
        expect(saveButton()).toBeEnabled();
        fireEvent.click(saveButton());
        expect(save).not.toHaveBeenCalled();
        expect(screen.getByText('label.noChanges')).toBeInTheDocument();
        expect(saveButton()).toHaveFocus();
    });

    test('the success message stays until the next edit', async () => {
        jest.useFakeTimers();
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        await act(async () => {
            fireEvent.click(saveButton());
        });
        act(() => {
            jest.advanceTimersByTime(60000);
        });
        expect(screen.getByText('label.saveSuccess')).toBeInTheDocument();

        fireEvent.keyDown(item(mutationsTree(), 'admin'), {key: ' '});
        expect(screen.queryByText('label.saveSuccess')).not.toBeInTheDocument();
    });

    test('Discard restores the loaded allow list', () => {
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'admin'), {key: ' '});
        confirmEmptying();
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'false');
        fireEvent.click(screen.getByRole('button', {name: 'label.discard'}));
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'true');
        expect(screen.queryByText('label.unsavedChanges')).not.toBeInTheDocument();
    });

    test('leaving the page with unsaved changes is guarded', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        const clean = new Event('beforeunload', {cancelable: true});
        window.dispatchEvent(clean);
        expect(clean.defaultPrevented).toBe(false);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        const dirty = new Event('beforeunload', {cancelable: true});
        window.dispatchEvent(dirty);
        expect(dirty.defaultPrevented).toBe(true);
    });
});

describe('policy in force and pending policy (UX-4)', () => {
    test('without edits, the banner states the policy in force only', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.getByText('label.policyReadOnly')).toBeInTheDocument();
        expect(screen.queryByText('label.policyPendingPrefix')).not.toBeInTheDocument();
        expect(screen.queryByText(/label\.policyInForce/)).not.toBeInTheDocument();
    });

    test('with unsaved edits, the banner shows the pending policy and the one the server still applies', async () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        const pending = policyText('label.policyRestricted');
        expect(pending).toHaveTextContent('label.policyPendingPrefix label.policyRestricted');
        expect(screen.getByText('label.policyInForce')).toBeInTheDocument();
        expect(screen.getByText('label.policyReadOnly')).toBeInTheDocument();

        fireEvent.click(saveButton());
        await screen.findByText('label.saveSuccess');
        expect(screen.queryByText('label.policyPendingPrefix')).not.toBeInTheDocument();
        expect(screen.queryByText('label.policyReadOnly')).not.toBeInTheDocument();
    });
});

describe('cluster (UX-2)', () => {
    test('on a single server, no cluster note and the plain success message', async () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.queryByText('label.clusterNote')).not.toBeInTheDocument();
        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        fireEvent.click(saveButton());
        expect(await screen.findByText('label.saveSuccess')).toBeInTheDocument();
    });

    test('in a cluster, the panel says the list is per node', () => {
        installApolloMock({clustered: true});
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.getByText('label.clusterNote')).toBeInTheDocument();
    });

    test('in a cluster, the success message says only this node was saved', async () => {
        installApolloMock({clustered: true});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        fireEvent.click(saveButton());
        expect(await screen.findByText('label.saveSuccessCluster')).toBeInTheDocument();
        expect(screen.queryByText('label.saveSuccess')).not.toBeInTheDocument();
    });
});

describe('bulk actions and confirmation', () => {
    test('clearing the allow list asks for confirmation; Cancel and Escape keep it', () => {
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        const clear = screen.getByRole('button', {name: 'label.clearAll'});
        act(() => clear.focus());
        fireEvent.click(clear);
        const dialog = screen.getByRole('alertdialog', {name: 'label.confirmClearTitle'});
        expect(within(dialog).getByRole('button', {name: 'label.cancel'})).toHaveFocus();
        fireEvent.click(within(dialog).getByRole('button', {name: 'label.cancel'}));
        expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
        expect(clear).toHaveFocus();

        fireEvent.click(clear);
        fireEvent.keyDown(screen.getByRole('alertdialog'), {key: 'Escape'});
        expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'true');
    });

    test('confirming the clear empties the list and restores the read-only default', async () => {
        const {save} = installApolloMock({whitelist: ['query:admin', 'mutation:admin']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(screen.getByRole('button', {name: 'label.clearAll'}));
        fireEvent.click(screen.getByRole('button', {name: 'label.confirmClearAction'}));
        expect(policyText('label.policyReadOnly')).toBeInTheDocument();
        // The opener is gone with the list: focus moves to the panel heading, never to <body>.
        expect(screen.queryByRole('button', {name: 'label.clearAll'})).not.toBeInTheDocument();
        await waitFor(() => expect(panelHeading()).toHaveFocus());
        fireEvent.click(saveButton());

        await waitFor(() => expect(save).toHaveBeenCalledWith({variables: {whitelist: []}}));
    });

    test('the dialog keeps keyboard focus inside', () => {
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(screen.getByRole('button', {name: 'label.clearAll'}));
        const dialog = screen.getByRole('alertdialog');
        const [cancel, confirm] = within(dialog).getAllByRole('button');
        act(() => confirm.focus());
        fireEvent.keyDown(dialog, {key: 'Tab'});
        expect(cancel).toHaveFocus();
        fireEvent.keyDown(dialog, {key: 'Tab', shiftKey: true});
        expect(confirm).toHaveFocus();
    });

    test('a click on the backdrop cannot move focus out of the dialog', () => {
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(screen.getByRole('button', {name: 'label.clearAll'}));
        const dialog = screen.getByRole('alertdialog');
        const cancel = within(dialog).getByRole('button', {name: 'label.cancel'});
        // The backdrop swallows the mouse-down that would blur the dialog.
        expect(fireEvent.mouseDown(dialog.parentElement)).toBe(false);
        // Focus that still reaches the page behind is sent back to Cancel.
        act(() => {
            screen.getByRole('searchbox', {name: 'label.filterLabel'}).focus();
        });
        expect(cancel).toHaveFocus();
        // A mouse-down inside the dialog keeps its default behaviour.
        expect(fireEvent.mouseDown(cancel)).toBe(true);
    });

    test('focus moves freely again once the dialog is closed', () => {
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(screen.getByRole('button', {name: 'label.clearAll'}));
        fireEvent.keyDown(screen.getByRole('alertdialog'), {key: 'Escape'});
        const filterBox = screen.getByRole('searchbox', {name: 'label.filterLabel'});
        act(() => filterBox.focus());
        expect(filterBox).toHaveFocus();
    });

    test('allowing all mutations is confirmed and never includes communityMcp', async () => {
        const {save} = installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(screen.getByRole('button', {name: 'label.allowAllMutations'}));
        fireEvent.click(screen.getByRole('button', {name: 'label.confirmAllowMutationsAction'}));
        expect(item(mutationsTree(), 'admin')).toHaveAttribute('aria-checked', 'true');
        expect(item(mutationsTree(), 'communityMcp')).toHaveAttribute('aria-checked', 'false');
        fireEvent.click(saveButton());

        await waitFor(() => expect(save).toHaveBeenCalledWith({variables: {whitelist: ['mutation:admin']}}));
    });

    test('per-group actions allow and clear only their own group', () => {
        installApolloMock({whitelist: ['query:admin.jahia']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(screen.getByRole('button', {name: 'label.allowAllQueries'}));
        // The admin entry chosen deliberately is kept; the namespace itself is not added (UX-1).
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'mixed');
        expect(item(queriesTree(), 'currentUser')).toHaveAttribute('aria-checked', 'true');

        fireEvent.keyDown(item(mutationsTree(), 'admin'), {key: ' '});
        fireEvent.click(screen.getByRole('button', {name: 'label.clearMutations'}));
        expect(item(mutationsTree(), 'admin')).toHaveAttribute('aria-checked', 'false');
        expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();

        fireEvent.click(screen.getByRole('button', {name: 'label.clearQueries'}));
        confirmEmptying();
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'false');
    });

    test('"Allow all queries" leaves the admin query namespace unchecked (UX-1)', async () => {
        const {save} = installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(screen.getByRole('button', {name: 'label.allowAllQueries'}));
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'false');
        expect(item(queriesTree(), 'currentUser')).toHaveAttribute('aria-checked', 'true');
        fireEvent.click(saveButton());
        await waitFor(() => expect(save).toHaveBeenCalledWith({variables: {whitelist: ['query:currentUser']}}));
    });

    test('the admin query row warns that it exposes configuration values', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        expect(item(queriesTree(), 'admin')).toHaveAccessibleDescription('label.adminQueryNode Admin queries');
        expect(item(mutationsTree(), 'admin')).not.toHaveAccessibleDescription(expect.stringContaining('label.adminQueryNode'));
    });

    test('checking the admin query asks for confirmation; Cancel leaves it unchecked', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'admin'), {key: ' '});
        const dialog = screen.getByRole('alertdialog', {name: 'label.confirmAllowAdminTitle'});
        expect(dialog).toHaveAccessibleDescription('label.confirmAllowAdminBody');
        fireEvent.click(within(dialog).getByRole('button', {name: 'label.cancel'}));
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'false');
    });

    test('checking the admin query is applied once confirmed; unchecking it is not confirmed', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'admin'), {key: ' '});
        fireEvent.click(screen.getByRole('button', {name: 'label.confirmAllowAdminAction'}));
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'true');

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        fireEvent.keyDown(item(queriesTree(), 'admin'), {key: ' '});
        expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument();
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'false');
    });

    test('a privilege-granting mutation field is locked in the tree (ARCH-5)', async () => {
        const querySpy = jest.fn().mockResolvedValue({data: {typeFields: {fields: [
            {name: 'personalApiTokens', description: '', type: {name: 'TokensMutation', kind: 'OBJECT'}}
        ]}}});
        installApolloMock({querySpy});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(mutationsTree(), 'admin'), {key: 'ArrowRight'});
        const tokens = await within(mutationsTree()).findByRole('treeitem', {name: 'personalApiTokens'});
        expect(tokens).toHaveAccessibleDescription('label.lockedPrivilegeNode');
        fireEvent.keyDown(tokens, {key: ' '});
        expect(tokens).toHaveAttribute('aria-checked', 'false');
    });

    test('a stored privilege-granting entry is listed as a problem with its own reason', () => {
        installApolloMock({whitelist: ['mutation:admin.personalApiTokens', 'query:currentUser']});
        render(<CommunityMcpConfigAdmin/>);

        expect(within(problemsRegion()).getByText('label.problemLockedPrivilege')).toBeInTheDocument();
    });

    test('clearing queries while mutations stay checked asks for confirmation', () => {
        installApolloMock({whitelist: ['query:admin', 'mutation:admin']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(screen.getByRole('button', {name: 'label.clearQueries'}));
        fireEvent.click(screen.getByRole('button', {name: 'label.confirmClearQueriesAction'}));
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'false');
        expect(item(mutationsTree(), 'admin')).toHaveAttribute('aria-checked', 'true');
    });
});

describe('entries outside the tree', () => {
    test('entries missing from the schema or refused by the server are listed and removable', () => {
        installApolloMock({whitelist: ['query:gone', 'mutation:communityMcp', 'query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        const problems = screen.getByRole('region', {name: 'label.problemEntriesTitle'});
        expect(within(problems).getByText('label.problemUnknown')).toBeInTheDocument();
        expect(within(problems).getByText('label.problemLocked')).toBeInTheDocument();

        fireEvent.click(within(problems).getByRole('button', {name: 'label.remove query:gone'}));
        expect(within(problems).queryByText('query:gone')).not.toBeInTheDocument();
        expect(screen.getByText('label.unsavedChanges')).toBeInTheDocument();
    });

    test('every entry can be reviewed and removed from the full list', () => {
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(screen.getByRole('button', {name: 'label.remove query:admin'}));
        confirmEmptying();
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'false');
        expect(screen.getByText('label.noEntries')).toBeInTheDocument();
    });
});

describe('filter', () => {
    test('filters root operations by name', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.change(screen.getByLabelText('label.filterLabel'), {target: {value: 'current'}});
        expect(item(queriesTree(), 'currentUser')).toBeInTheDocument();
        expect(within(queriesTree()).queryByRole('treeitem', {name: 'admin'})).not.toBeInTheDocument();
        expect(screen.queryByRole('tree', {name: 'label.mutationsHeading'})).not.toBeInTheDocument();
    });

    test('a loaded descendant that matches keeps its parent visible and expanded', async () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'admin'), {key: 'ArrowRight'});
        await within(queriesTree()).findByRole('treeitem', {name: 'version'});
        fireEvent.keyDown(item(queriesTree(), 'admin'), {key: 'ArrowLeft'});

        fireEvent.change(screen.getByLabelText('label.filterLabel'), {target: {value: 'vers'}});
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-expanded', 'true');
        expect(item(queriesTree(), 'version')).toBeInTheDocument();
        expect(within(queriesTree()).queryByRole('treeitem', {name: 'jahia'})).not.toBeInTheDocument();
    });

    test('says when nothing matches', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.change(screen.getByLabelText('label.filterLabel'), {target: {value: 'zzz'}});
        expect(screen.getByText('label.filterNoMatch')).toBeInTheDocument();
    });
});

describe('keyboard navigation (APG tree)', () => {
    test('one tab stop per tree; arrows, Home and End move focus', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        const admin = item(queriesTree(), 'admin');
        const user = item(queriesTree(), 'currentUser');
        expect(admin).toHaveAttribute('tabindex', '0');
        expect(user).toHaveAttribute('tabindex', '-1');

        act(() => admin.focus());
        fireEvent.keyDown(admin, {key: 'ArrowDown'});
        expect(user).toHaveFocus();
        expect(user).toHaveAttribute('tabindex', '0');
        expect(admin).toHaveAttribute('tabindex', '-1');
        fireEvent.keyDown(user, {key: 'ArrowUp'});
        expect(admin).toHaveFocus();
        fireEvent.keyDown(admin, {key: 'End'});
        expect(user).toHaveFocus();
        fireEvent.keyDown(user, {key: 'Home'});
        expect(admin).toHaveFocus();
        // Unhandled keys are left alone.
        fireEvent.keyDown(admin, {key: 'a'});
        expect(admin).toHaveFocus();
    });

    test('Right expands then enters the children; Left goes to the parent then collapses', async () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        const admin = item(queriesTree(), 'admin');
        act(() => admin.focus());
        fireEvent.keyDown(admin, {key: 'ArrowRight'});
        expect(admin).toHaveAttribute('aria-expanded', 'true');
        const jahia = await within(queriesTree()).findByRole('treeitem', {name: 'jahia'});
        expect(jahia).toHaveAttribute('aria-posinset', '1');
        expect(jahia).toHaveAttribute('aria-setsize', '2');

        fireEvent.keyDown(admin, {key: 'ArrowRight'});
        expect(jahia).toHaveFocus();
        fireEvent.keyDown(jahia, {key: 'ArrowLeft'});
        expect(admin).toHaveFocus();
        fireEvent.keyDown(admin, {key: 'ArrowLeft'});
        expect(admin).toHaveAttribute('aria-expanded', 'false');
        expect(within(queriesTree()).queryByRole('treeitem', {name: 'jahia'})).not.toBeInTheDocument();
    });

    test('Space toggles the focused item', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        const user = item(queriesTree(), 'currentUser');
        fireEvent.keyDown(user, {key: ' '});
        expect(user).toHaveAttribute('aria-checked', 'true');
        fireEvent.keyDown(user, {key: ' '});
        confirmEmptying();
        expect(user).toHaveAttribute('aria-checked', 'false');
    });
});

describe('every edit that empties the list is confirmed (it widens access to every query)', () => {
    test('Clear queries with no mutation left asks; Cancel keeps the list', () => {
        installApolloMock({whitelist: ['query:currentUser']});
        render(<CommunityMcpConfigAdmin/>);

        act(() => screen.getByRole('button', {name: 'label.clearQueries'}).focus());
        fireEvent.click(screen.getByRole('button', {name: 'label.clearQueries'}));
        const dialog = screen.getByRole('alertdialog', {name: 'label.confirmClearTitle'});
        fireEvent.click(within(dialog).getByRole('button', {name: 'label.cancel'}));
        expect(item(queriesTree(), 'currentUser')).toHaveAttribute('aria-checked', 'true');
        expect(screen.getByRole('button', {name: 'label.clearQueries'})).toHaveFocus();

        fireEvent.click(screen.getByRole('button', {name: 'label.clearQueries'}));
        confirmEmptying();
        expect(policyText('label.policyReadOnly')).toBeInTheDocument();
    });

    test('Clear mutations that leaves nothing asks', () => {
        installApolloMock({whitelist: ['mutation:admin']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(screen.getByRole('button', {name: 'label.clearMutations'}));
        expect(screen.getByRole('alertdialog', {name: 'label.confirmClearTitle'})).toBeInTheDocument();
        confirmEmptying();
        expect(item(mutationsTree(), 'admin')).toHaveAttribute('aria-checked', 'false');
        expect(policyText('label.policyReadOnly')).toBeInTheDocument();
    });

    test('unchecking the last item asks; Cancel keeps it and returns focus to the item', async () => {
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        const admin = item(queriesTree(), 'admin');
        act(() => admin.focus());
        fireEvent.keyDown(admin, {key: ' '});
        fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', {name: 'label.cancel'}));
        expect(admin).toHaveAttribute('aria-checked', 'true');
        expect(admin).toHaveFocus();
        expect(screen.queryByText('label.unsavedChanges')).not.toBeInTheDocument();
    });

    test('removing the last entry asks, then focus moves to the (now empty) entry list', async () => {
        const {save} = installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        const remove = screen.getByRole('button', {name: 'label.remove query:admin'});
        act(() => remove.focus());
        fireEvent.click(remove);
        confirmEmptying();
        await waitFor(() => expect(screen.getByText('label.allEntriesSummary')).toHaveFocus());
        fireEvent.click(saveButton());
        await waitFor(() => expect(save).toHaveBeenCalledWith({variables: {whitelist: []}}));
    });
});

describe('focus never falls to the page body', () => {
    test('Discard moves focus to Save', async () => {
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        const discard = screen.getByRole('button', {name: 'label.discard'});
        act(() => discard.focus());
        fireEvent.click(discard);
        expect(screen.queryByRole('button', {name: 'label.discard'})).not.toBeInTheDocument();
        await waitFor(() => expect(saveButton()).toHaveFocus());
        expect(liveRegion()).toHaveTextContent('label.discarded');
    });

    test('Dismiss moves focus to Save', async () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(saveButton());
        const dismiss = screen.getByRole('button', {name: 'label.dismiss'});
        act(() => dismiss.focus());
        fireEvent.click(dismiss);
        expect(screen.queryByRole('button', {name: 'label.dismiss'})).not.toBeInTheDocument();
        await waitFor(() => expect(saveButton()).toHaveFocus());
    });

    test('Remove moves focus to the next entry, or the previous one at the end of the list', async () => {
        installApolloMock({whitelist: ['query:admin', 'query:currentUser', 'mutation:admin']});
        render(<CommunityMcpConfigAdmin/>);

        // Sorted: mutation:admin, query:admin, query:currentUser
        const first = screen.getByRole('button', {name: 'label.remove query:admin'});
        act(() => first.focus());
        fireEvent.click(first);
        await waitFor(() => expect(screen.getByRole('button', {name: 'label.remove query:currentUser'})).toHaveFocus());

        fireEvent.click(screen.getByRole('button', {name: 'label.remove query:currentUser'}));
        await waitFor(() => expect(screen.getByRole('button', {name: 'label.remove mutation:admin'})).toHaveFocus());
        expect(liveRegion()).toHaveTextContent('label.entryRemoved');
    });

    test('removing the last problem entry moves focus to the panel heading', async () => {
        installApolloMock({whitelist: ['query:gone', 'query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        const problems = screen.getByRole('region', {name: 'label.problemEntriesTitle'});
        const remove = within(problems).getByRole('button', {name: 'label.remove query:gone'});
        act(() => remove.focus());
        fireEvent.click(remove);
        expect(screen.queryByRole('region', {name: 'label.problemEntriesTitle'})).not.toBeInTheDocument();
        await waitFor(() => expect(panelHeading()).toHaveFocus());
    });
});

describe('entries the server ignores are kept, shown and never dropped silently', () => {
    test('a list holding only a locked entry says every operation is refused', () => {
        installApolloMock({whitelist: ['mutation:communityMcp']});
        render(<CommunityMcpConfigAdmin/>);

        expect(policyText('label.policyDenyAll')).toBeInTheDocument();
        expect(screen.queryByText('label.policyNoQuery')).not.toBeInTheDocument();
    });

    test('a list holding only an unknown operation type says every operation is refused', () => {
        installApolloMock({whitelist: ['subscription:x']});
        render(<CommunityMcpConfigAdmin/>);

        expect(policyText('label.policyDenyAll')).toBeInTheDocument();
        expect(screen.queryByText('label.policyReadOnly')).not.toBeInTheDocument();
        expect(screen.queryByText('label.migrated')).not.toBeInTheDocument();
        expect(screen.queryByText('label.unsavedChanges')).not.toBeInTheDocument();
        const problems = problemsRegion();
        expect(within(problems).getByText('subscription:x', {selector: 'code'})).toBeInTheDocument();
        expect(within(problems).getByText('label.problemInvalid')).toBeInTheDocument();
    });

    test('Save is refused until invalid or locked entries are removed', async () => {
        const {save} = installApolloMock({whitelist: ['subscription:x', 'mutation:communityMcp', 'query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        fireEvent.click(saveButton());
        expect(save).not.toHaveBeenCalled();
        expect(screen.getByRole('alert')).toHaveTextContent('label.saveBlocked');

        fireEvent.click(within(problemsRegion()).getByRole('button', {name: 'label.remove subscription:x'}));
        fireEvent.click(within(problemsRegion()).getByRole('button', {name: 'label.remove mutation:communityMcp'}));
        fireEvent.click(saveButton());
        await waitFor(() => expect(save).toHaveBeenCalledWith({variables: {whitelist: ['query:admin', 'query:currentUser']}}));
    });

    test('removing the only invalid entry is confirmed, since access widens to every query', async () => {
        const {save} = installApolloMock({whitelist: ['subscription:x']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(within(problemsRegion()).getByRole('button', {name: 'label.remove subscription:x'}));
        confirmEmptying();
        expect(policyText('label.policyReadOnly')).toBeInTheDocument();
        fireEvent.click(saveButton());
        await waitFor(() => expect(save).toHaveBeenCalledWith({variables: {whitelist: []}}));
    });

    test('Discard brings an invalid entry back', () => {
        installApolloMock({whitelist: ['foo:bar', 'query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(within(problemsRegion()).getByRole('button', {name: 'label.remove foo:bar'}));
        expect(screen.queryByText('foo:bar', {selector: 'code'})).not.toBeInTheDocument();
        fireEvent.click(screen.getByRole('button', {name: 'label.discard'}));
        expect(within(problemsRegion()).getByText('foo:bar', {selector: 'code'})).toBeInTheDocument();
    });

    test('a locked configuration entry is listed with its own reason', () => {
        installApolloMock({whitelist: ['mutation:admin.jahia.configuration', 'query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        const problems = screen.getByRole('region', {name: 'label.problemEntriesTitle'});
        expect(within(problems).getByText('label.problemLockedConfiguration')).toBeInTheDocument();
    });
});

describe('announcements', () => {
    test('the policy text is not a live region; a policy change is announced once', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        expect(policyText('label.policyReadOnly').closest('[role="status"]')).toBeNull();
        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        expect(liveRegion()).toHaveTextContent('label.policyRestricted');
        // Same policy after the next edit: nothing new is written to the live region.
        const announced = liveRegion().firstChild;
        fireEvent.keyDown(item(mutationsTree(), 'admin'), {key: ' '});
        expect(liveRegion().firstChild).toBe(announced);
    });

    test('the same message twice is announced twice (new node in the live region)', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        const locked = item(mutationsTree(), 'communityMcp');
        fireEvent.keyDown(locked, {key: ' '});
        const first = liveRegion().firstChild;
        fireEvent.keyDown(locked, {key: ' '});
        expect(liveRegion()).toHaveTextContent('label.lockedNode');
        expect(liveRegion().firstChild).not.toBe(first);
    });

    test('a repeated save status is re-announced', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(saveButton());
        const first = screen.getByText('label.noChanges');
        fireEvent.click(saveButton());
        expect(screen.getByText('label.noChanges')).not.toBe(first);
    });
});
