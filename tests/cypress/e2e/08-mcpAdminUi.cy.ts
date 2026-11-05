import {expectWhitelistUnchanged, setWhitelist, waitForWhitelist} from '../support/mcp';

// Smoke test of the admin panel registered by register.jsx under
// administration-server-configuration (route id: jahiaMcpCommunityServer).
describe('MCP Server — Admin panel smoke test', () => {
    const ROUTE = '/jahia/administration/jahiaMcpCommunityServer';
    const ENTRY = 'query:currentUser';

    before(() => {
        cy.login();
    });

    beforeEach(() => {
        setWhitelist([]);
        cy.login();
        cy.visit(ROUTE);
    });

    after(() => {
        setWhitelist([]);
    });

    // Selectors are ids, roles, ARIA state and data attributes: no CSS-module class (hashed at build
    // time) and no localized text, so the spec runs under any admin language.
    const treeItem = (path: string) => cy.get(`[role="treeitem"][data-path="${path}"]`, {timeout: 60000}).first();
    // The visible row is the mouse target; its label (aria-labelledby) sits inside it, so a click on
    // the label reaches the row's click handler as a user's click would.
    const clickRow = (path: string) => treeItem(path).invoke('attr', 'aria-labelledby')
        .then(labelId => cy.get(`[id="${labelId}"]`).click());
    const saveBar = () => cy.get('#mcp-save').closest('[role="region"]');

    it('loads at the admin route and shows the Queries and Mutations groups', () => {
        cy.get('#mcp-title', {timeout: 60000}).should('be.visible').and('not.be.empty');
        cy.get('section[aria-labelledby="mcp-group-queries"]', {timeout: 60000}).find('[role="tree"]').should('exist');
        cy.get('section[aria-labelledby="mcp-group-mutations"]').find('[role="tree"]').should('exist');
        cy.get('[role="tree"]').should('have.length.at.least', 2);
    });

    it('persists a checked entry when saved', () => {
        clickRow(ENTRY);
        treeItem(ENTRY).should('have.attr', 'aria-checked', 'true');
        cy.get('#mcp-save').click();
        // The save outcome is announced in the polite <output>, and no error alert is shown.
        saveBar().find('output', {timeout: 30000}).should('not.be.empty');
        saveBar().find('[role="alert"]').should('not.exist');
        // The server applies the saved configuration asynchronously: wait for it before reloading.
        waitForWhitelist([ENTRY]);
        // ...and the panel shows it after a reload.
        cy.reload();
        treeItem(ENTRY).should('have.attr', 'aria-checked', 'true');
    });

    // "Allow all queries" checks every query root except admin, which exposes configuration values,
    // secrets included: the read-only default refuses it, and the bulk action must not open it.
    it('allows all queries except the admin namespace', () => {
        treeItem('query:jcr').should('have.attr', 'aria-checked', 'false');
        // The group's bulk actions sit in its header; "Allow all queries" is the first one.
        cy.get('#mcp-group-queries').parent().find('button').first().click();
        treeItem('query:jcr').should('have.attr', 'aria-checked', 'true');
        treeItem('query:currentUser').should('have.attr', 'aria-checked', 'true');
        treeItem('query:admin').should('have.attr', 'aria-checked', 'false');
    });

    // The admin.jahia.configuration mutation could rewrite this module's own configuration: the tree shows it,
    // locked, under Mutations > admin > jahia, and checking it changes nothing.
    it('shows mutation:admin.jahia.configuration as locked and does not let it be checked', () => {
        const LOCKED = 'mutation:admin.jahia.configuration';
        const expand = (path: string) => {
            treeItem(path).find('[data-testid="mcp-toggle"]').first().click();
            treeItem(path).should('have.attr', 'aria-expanded', 'true');
        };

        expand('mutation:admin');
        expand('mutation:admin.jahia');
        // Locked: unchecked, not expandable, and described by its lock note.
        treeItem(LOCKED).should('have.attr', 'aria-checked', 'false')
            .and('not.have.attr', 'aria-expanded');
        // The lock note's id ends in '-locked' (OperationTree.jsx); its text is localized, so only its presence is checked.
        treeItem(LOCKED).invoke('attr', 'id').then(itemId => {
            treeItem(LOCKED).should('have.attr', 'aria-describedby').and('include', `${itemId}-locked`);
            cy.get(`[id="${itemId}-locked"]`).should('not.be.empty');
        });
        clickRow(LOCKED);
        treeItem(LOCKED).should('have.attr', 'aria-checked', 'false');
        // Nothing to discard: the click left the list unchanged, so the save bar holds Save alone.
        saveBar().find('button:not(#mcp-save)').should('not.exist');
        // Saving now must store nothing: the stored and served list stay empty.
        cy.get('#mcp-save').click();
        saveBar().find('output', {timeout: 30000}).should('not.be.empty');
        saveBar().find('[role="alert"]').should('not.exist');
        expectWhitelistUnchanged([]);
    });
});
