import React from 'react';
import {act, fireEvent, render, screen, waitFor, within} from '@testing-library/react';
import {CommunityMcpConfigAdmin} from '../CommunityMcpConfig';

// Mounts the real admin editor against the __mocks__ Apollo/moonstone/i18next layer
// (the i18next mock echoes translation keys, so visible strings are the raw keys).

const QUERY_FIELDS = [
    {name: 'admin', description: 'Admin queries', type: {name: 'AdminQuery', kind: 'OBJECT'}},
    {name: 'currentUser', description: '', type: {name: 'User', kind: 'OBJECT'}}
];
const MUTATION_FIELDS = [
    {name: 'admin', description: '', type: {name: 'AdminMutation', kind: 'OBJECT'}},
    {name: 'communityMcp', description: '', type: {name: 'McpMutation', kind: 'OBJECT'}}
];
const ADMIN_TYPE_FIELDS = [
    {name: 'jahia', description: '', type: {name: 'JahiaQuery', kind: 'OBJECT'}},
    {name: 'version', description: '', type: {name: 'String', kind: 'SCALAR'}}
];

const ok = {data: {communityMcp: {saveSettings: true}}};

function installApolloMock({whitelist = [], saveSpy, querySpy, errors = {}, loading = false, refetch} = {}) {
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
        settings: make({communityMcp: {settings: {whitelist}}}, errors.settings)
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

const queriesTree = () => screen.getByRole('tree', {name: 'label.queriesHeading'});
const mutationsTree = () => screen.getByRole('tree', {name: 'label.mutationsHeading'});
const item = (tree, name) => within(tree).getByRole('treeitem', {name});
const saveButton = () => screen.getByRole('button', {name: 'label.save'});

afterEach(() => {
    delete window.__apolloMock;
    jest.useRealTimers();
});

describe('tree rendering and selection', () => {
    test('renders query and mutation roots in two trees, named by their visible name', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-level', '1');
        expect(item(queriesTree(), 'currentUser')).toBeInTheDocument();
        expect(item(mutationsTree(), 'admin')).toBeInTheDocument();
        // Descriptions are visible text and announced as the item description.
        expect(item(queriesTree(), 'admin')).toHaveAccessibleDescription('Admin queries');
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

        fireEvent.keyDown(item(queriesTree(), 'admin'), {key: ' '});
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

        fireEvent.keyDown(item(queriesTree(), 'admin'), {key: 'Enter'});
        fireEvent.click(saveButton());

        const alert = await screen.findByRole('alert');
        expect(alert).toHaveTextContent('label.saveErrorServer');
        expect(within(alert).getByText('Invalid entry foo')).toHaveAttribute('lang', 'en');
    });

    test('an error without GraphQL details falls back to its message', async () => {
        installApolloMock({saveSpy: jest.fn().mockRejectedValue(new Error('Forbidden'))});
        jest.spyOn(console, 'error').mockImplementation(() => {});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'admin'), {key: ' '});
        fireEvent.click(saveButton());

        expect(await screen.findByText('Forbidden')).toBeInTheDocument();
    });

    test('a network failure shows the network message', async () => {
        const err = Object.assign(new Error('Failed to fetch'), {networkError: new Error('offline')});
        installApolloMock({saveSpy: jest.fn().mockRejectedValue(err)});
        jest.spyOn(console, 'error').mockImplementation(() => {});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'admin'), {key: ' '});
        fireEvent.click(saveButton());

        expect(await screen.findByRole('alert')).toHaveTextContent('label.saveNetworkError');
    });

    test('Save stays enabled and focused with no changes, and says so', () => {
        const {save} = installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        saveButton().focus();
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

        fireEvent.keyDown(item(queriesTree(), 'admin'), {key: ' '});
        await act(async () => {
            fireEvent.click(saveButton());
        });
        act(() => {
            jest.advanceTimersByTime(60000);
        });
        expect(screen.getByText('label.saveSuccess')).toBeInTheDocument();

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        expect(screen.queryByText('label.saveSuccess')).not.toBeInTheDocument();
    });

    test('Discard restores the loaded allow list', () => {
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'admin'), {key: ' '});
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

        fireEvent.keyDown(item(queriesTree(), 'admin'), {key: ' '});
        const dirty = new Event('beforeunload', {cancelable: true});
        window.dispatchEvent(dirty);
        expect(dirty.defaultPrevented).toBe(true);
    });
});

describe('bulk actions and confirmation', () => {
    test('clearing the allow list asks for confirmation; Cancel and Escape keep it', () => {
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        const clear = screen.getByRole('button', {name: 'label.clearAll'});
        clear.focus();
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
        expect(screen.getByText('label.policyReadOnly')).toBeInTheDocument();
        fireEvent.click(saveButton());

        await waitFor(() => expect(save).toHaveBeenCalledWith({variables: {whitelist: []}}));
    });

    test('the dialog keeps keyboard focus inside', () => {
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(screen.getByRole('button', {name: 'label.clearAll'}));
        const dialog = screen.getByRole('alertdialog');
        const [cancel, confirm] = within(dialog).getAllByRole('button');
        confirm.focus();
        fireEvent.keyDown(dialog, {key: 'Tab'});
        expect(cancel).toHaveFocus();
        fireEvent.keyDown(dialog, {key: 'Tab', shiftKey: true});
        expect(confirm).toHaveFocus();
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
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'true');
        expect(item(queriesTree(), 'currentUser')).toHaveAttribute('aria-checked', 'true');

        fireEvent.click(screen.getByRole('button', {name: 'label.clearQueries'}));
        expect(item(queriesTree(), 'admin')).toHaveAttribute('aria-checked', 'false');

        fireEvent.keyDown(item(mutationsTree(), 'admin'), {key: ' '});
        fireEvent.click(screen.getByRole('button', {name: 'label.clearMutations'}));
        expect(item(mutationsTree(), 'admin')).toHaveAttribute('aria-checked', 'false');
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

        admin.focus();
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
        admin.focus();
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
        expect(user).toHaveAttribute('aria-checked', 'false');
    });
});
