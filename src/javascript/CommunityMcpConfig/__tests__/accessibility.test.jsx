import React from 'react';
import {act, fireEvent, render, screen, waitFor} from '@testing-library/react';
import {CommunityMcpConfigAdmin} from '../CommunityMcpConfig';
import {SaveBar} from '../SaveBar';
import {resolveLanguage} from '../i18n';
import {installApolloMock, item, queriesTree, saveButton} from './fixture';

const DRAFT_KEY = 'jahia-mcp-community-server:draft';
const authError = statusCode => Object.assign(new Error('Response not successful'), {networkError: {statusCode}});

afterEach(() => {
    delete window.__apolloMock;
    window.sessionStorage.clear();
    jest.restoreAllMocks();
});

describe('language of the panel (A11Y-1)', () => {
    test('the root declares the language its strings are written in', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.getByRole('region', {name: 'label.title'})).toHaveAttribute('lang', 'en');
    });

    test('resolveLanguage picks the first UI language that has a bundle, else English', () => {
        const withBundles = (languages, bundles, language) => ({
            languages, language, hasResourceBundle: (lng, ns) => ns === 'jahia-mcp-community-server' && bundles.includes(lng)
        });
        expect(resolveLanguage(withBundles(['fr-FR', 'fr', 'en'], ['en', 'fr']))).toBe('fr');
        expect(resolveLanguage(withBundles(['de', 'en'], ['en', 'fr']))).toBe('en');
        expect(resolveLanguage(withBundles(undefined, ['fr'], 'fr'))).toBe('fr');
        expect(resolveLanguage(withBundles(['de'], []))).toBe('en');
        expect(resolveLanguage(undefined)).toBe('en');
    });
});

describe('saving is visible (A11Y-6)', () => {
    test('a second click on Save while saving sends nothing more', async () => {
        let resolveSave;
        const saveSpy = jest.fn(() => new Promise(resolve => {
            resolveSave = resolve;
        }));
        installApolloMock({saveSpy});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        fireEvent.click(saveButton());
        expect(await screen.findByText('label.saving')).toBeVisible();
        fireEvent.click(saveButton());
        expect(saveSpy).toHaveBeenCalledTimes(1);
        await act(async () => resolveSave({data: {communityMcp: {saveSettings: true}}}));
        expect(screen.getByText('label.saveSuccess')).toBeInTheDocument();
    });

    const props = {isDirty: true, hasChangesToDiscard: true, isSaving: false, status: null, onSave: () => {}, onDiscard: () => {}, onDismiss: () => {}, t: k => k};

    test('"Saving…" is shown, not only announced, and Save carries no aria-busy', () => {
        render(<SaveBar {...props} isSaving/>);

        const output = screen.getByText('label.saving').closest('output');
        expect(output).toHaveClass('mcp_alert', 'mcp_alert--info');
        expect(output).not.toHaveClass('mcp_visuallyHidden');
        expect(screen.getByRole('button', {name: 'label.save'})).not.toHaveAttribute('aria-busy');
    });

    test('the unsaved-changes indicator sits in a status region, so its appearance is announced', () => {
        const {rerender} = render(<SaveBar {...props} isDirty={false}/>);
        const region = document.querySelector('[data-testid="mcp-unsaved"]');
        expect(region).toHaveAttribute('role', 'status');
        rerender(<SaveBar {...props}/>);
        expect(region).toHaveTextContent('label.unsavedChanges');
    });
});

describe('session expiry (A11Y-5)', () => {
    test.each([401, 403])('a save refused with HTTP %i says the session expired and keeps the edits', async status => {
        installApolloMock({saveSpy: jest.fn().mockRejectedValue(authError(status))});
        jest.spyOn(console, 'error').mockImplementation(() => {});
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        fireEvent.click(saveButton());

        expect(await screen.findByRole('alert')).toHaveTextContent('label.saveSessionExpired');
        expect(item(queriesTree(), 'currentUser')).toHaveAttribute('aria-checked', 'true');
        expect(screen.getByText('label.unsavedChanges')).toBeInTheDocument();
    });

    test('a load refused with HTTP 401 says the session expired', () => {
        installApolloMock({errors: {settings: authError(401)}});
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.getByRole('alert')).toHaveTextContent('label.loadSessionExpired');
    });

    test('unsaved edits are kept in this tab and removed once saved', async () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        expect(JSON.parse(window.sessionStorage.getItem(DRAFT_KEY))).toEqual({base: [], draft: ['query:currentUser']});

        fireEvent.click(saveButton());
        await screen.findByText('label.saveSuccess');
        expect(window.sessionStorage.getItem(DRAFT_KEY)).toBeNull();
    });

    test('a draft saved against the list the server still holds is restored, and said so', () => {
        window.sessionStorage.setItem(DRAFT_KEY, JSON.stringify({base: ['query:admin'], draft: ['query:admin', 'query:currentUser']}));
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        expect(item(queriesTree(), 'currentUser')).toHaveAttribute('aria-checked', 'true');
        expect(screen.getByText('label.draftRestored')).toBeInTheDocument();
        expect(screen.getByText('label.unsavedChanges')).toBeInTheDocument();
    });

    test('a draft made against another server list is dropped, never applied', () => {
        window.sessionStorage.setItem(DRAFT_KEY, JSON.stringify({base: [], draft: ['query:currentUser']}));
        installApolloMock({whitelist: ['query:admin']});
        render(<CommunityMcpConfigAdmin/>);

        expect(item(queriesTree(), 'currentUser')).toHaveAttribute('aria-checked', 'false');
        expect(screen.queryByText('label.draftRestored')).not.toBeInTheDocument();
        expect(window.sessionStorage.getItem(DRAFT_KEY)).toBeNull();
    });

    test('a corrupt draft is dropped', () => {
        window.sessionStorage.setItem(DRAFT_KEY, '{not json');
        jest.spyOn(console, 'warn').mockImplementation(() => {});
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        expect(screen.queryByText('label.draftRestored')).not.toBeInTheDocument();
        expect(window.sessionStorage.getItem(DRAFT_KEY)).toBeNull();
    });

    test('a draft that cannot be removed is still never applied', () => {
        window.sessionStorage.setItem(DRAFT_KEY, JSON.stringify({base: ['query:gone'], draft: ['query:currentUser']}));
        jest.spyOn(Storage.prototype, 'removeItem').mockImplementation(() => {
            throw new Error('denied');
        });
        const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        expect(item(queriesTree(), 'currentUser')).toHaveAttribute('aria-checked', 'false');
        expect(warn).toHaveBeenCalled();
    });

    test('unavailable storage only disables draft recovery', () => {
        jest.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
            throw new Error('denied');
        });
        jest.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
            throw new Error('denied');
        });
        const warn = jest.spyOn(console, 'warn').mockImplementation(() => {});
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.keyDown(item(queriesTree(), 'currentUser'), {key: ' '});
        expect(item(queriesTree(), 'currentUser')).toHaveAttribute('aria-checked', 'true');
        expect(warn).toHaveBeenCalled();
    });
});

describe('modal dialog (A11Y-12)', () => {
    test('keys other than Tab and Escape leave the dialog open', () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        fireEvent.click(screen.getByRole('button', {name: 'label.allowAllMutations'}));
        fireEvent.keyDown(screen.getByRole('alertdialog'), {key: 'ArrowDown'});
        expect(screen.getByRole('alertdialog')).toBeInTheDocument();
    });

    test('the page behind the dialog is inert while it is open, except the live region', async () => {
        installApolloMock();
        render(<CommunityMcpConfigAdmin/>);

        const title = screen.getByRole('heading', {name: 'label.title'});
        fireEvent.click(screen.getByRole('button', {name: 'label.allowAllMutations'}));
        expect(title).toHaveAttribute('inert');
        expect(document.getElementById('mcp-live')).not.toHaveAttribute('inert');
        expect(screen.getByRole('alertdialog').closest('[inert]')).toBeNull();

        act(() => {
            fireEvent.keyDown(screen.getByRole('alertdialog'), {key: 'Escape'});
        });
        await waitFor(() => expect(title).not.toHaveAttribute('inert'));
    });
});
