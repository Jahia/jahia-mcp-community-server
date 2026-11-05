import {DocumentNode} from 'graphql';
import {setWhitelist} from '../support/mcp';

// Smoke test of the admin panel registered by register.jsx under
// administration-server-configuration (route id: jahiaMcpCommunityServer).
describe('MCP Server — Admin panel smoke test', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql');
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
        cy.apollo({query: getSettings}).then(result => {
            expect(result.data.communityMcp.settings.whitelist).to.deep.eq([ENTRY]);
        });
        // ...and the panel shows it after a reload.
        cy.reload();
        cy.get(`[role="treeitem"][data-path="${ENTRY}"]`, {timeout: 60000}).first()
            .should('have.attr', 'aria-checked', 'true');
    });
});
