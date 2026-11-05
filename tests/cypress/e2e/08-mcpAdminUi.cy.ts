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

    it('loads at the admin route and shows the Queries and Mutations groups', () => {
        cy.get('#mcp-title', {timeout: 60000}).should('be.visible').and('contain.text', 'MCP Server');
        cy.get('#mcp-group-queries', {timeout: 60000}).should('contain.text', 'Queries');
        cy.get('#mcp-group-mutations').should('contain.text', 'Mutations');
        cy.get('[role="tree"]').should('have.length.at.least', 2);
    });

    it('persists a checked entry when saved', () => {
        cy.get(`[role="treeitem"][data-path="${ENTRY}"]`, {timeout: 60000}).first().as('item');
        cy.get('@item').find('[class*="mcp_treeRow"]').first().click();
        cy.get('@item').should('have.attr', 'aria-checked', 'true');
        cy.get('#mcp-save').click();
        cy.contains('Settings saved successfully.', {timeout: 30000}).should('be.visible');
        // The server applies the saved configuration asynchronously: wait for it before reloading.
        waitForWhitelist([ENTRY]);
        // ...and the panel shows it after a reload.
        cy.reload();
        cy.get(`[role="treeitem"][data-path="${ENTRY}"]`, {timeout: 60000}).first()
            .should('have.attr', 'aria-checked', 'true');
    });

    // The admin.jahia.configuration mutation could rewrite this module's own configuration: the tree shows it,
    // locked, under Mutations > admin > jahia, and checking it changes nothing.
    it('shows mutation:admin.jahia.configuration as locked and does not let it be checked', () => {
        const node = (path: string) => cy.get(`[role="treeitem"][data-path="${path}"]`, {timeout: 60000}).first();
        const expand = (path: string) => {
            node(path).find('[data-testid="mcp-toggle"]').first().click();
            node(path).should('have.attr', 'aria-expanded', 'true');
        };

        expand('mutation:admin');
        expand('mutation:admin.jahia');
        node('mutation:admin.jahia.configuration').as('locked');
        cy.get('@locked').should('have.attr', 'aria-checked', 'false')
            .and('not.have.attr', 'aria-expanded');
        cy.get('@locked').find('[class*="mcp_treeRow"]').first()
            .should('contain.text', 'the server always refuses changes to Jahia\'s OSGi configuration')
            .click();
        cy.get('@locked').should('have.attr', 'aria-checked', 'false');
        cy.get('[class*="mcp_unsaved"]').should('not.exist');
        expectWhitelistUnchanged([]);
    });
});
