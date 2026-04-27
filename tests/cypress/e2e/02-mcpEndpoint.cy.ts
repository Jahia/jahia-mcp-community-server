import {DocumentNode} from 'graphql';

describe('MCP Server — Endpoint Access Control', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const saveSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSettings.graphql');

    const callTool = (name: string, args: Record<string, unknown>) => {
        return cy.request({
            method: 'POST',
            url: '/modules/mcp',
            auth: {
                user: 'root',
                pass: Cypress.env('SUPER_USER_PASSWORD') || 'root1234'
            },
            headers: {'Content-Type': 'application/json'},
            body: {
                jsonrpc: '2.0',
                id: 1,
                method: 'tools/call',
                params: {name, arguments: args}
            }
        });
    };

    const executeGraphQL = (query: string) => callTool('executeGraphQL', {query});

    before(() => {
        cy.login();
    });

    afterEach(() => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: []}});
    });

    // --- No whitelist ---

    it('allows any operation when whitelist is empty', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: []}});
        executeGraphQL('{ currentUser { name } }')
            .its('body.result.isError').should('eq', false);
    });

    // --- Whitelist enforcement ---

    it('allows a whitelisted top-level operation', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        executeGraphQL('{ currentUser { name } }')
            .its('body.result.isError').should('eq', false);
    });

    it('blocks a non-whitelisted top-level operation', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        executeGraphQL('{ admin { jahia { isAlive } } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('not in whitelist');
        });
    });

    it('blocks every operation when the whitelist contains only an unrelated entry', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['jcr']}});
        executeGraphQL('{ currentUser { name } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('not in whitelist');
        });
    });

    // --- Dot-path coverage ---

    it('allows sub-paths when a parent dot-path is whitelisted', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['admin']}});
        executeGraphQL('{ admin { jahia { isAlive } } }')
            .its('body.result.isError').should('eq', false);
    });

    it('allows an exact dot-path match', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['admin.jahia']}});
        executeGraphQL('{ admin { jahia { isAlive } } }')
            .its('body.result.isError').should('eq', false);
    });

    it('blocks a sibling path not covered by the whitelisted dot-path', () => {
        // Whitelist covers admin.jahia but NOT currentUser
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['admin.jahia']}});
        executeGraphQL('{ currentUser { name } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('not in whitelist');
        });
    });

    // --- Introspection always passes ---

    it('allows __schema introspection regardless of whitelist', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        executeGraphQL('{ __schema { types { name } } }')
            .its('body.result.isError').should('eq', false);
    });

    it('allows __type introspection regardless of whitelist', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        executeGraphQL('{ __type(name: "Query") { fields { name } } }')
            .its('body.result.isError').should('eq', false);
    });

    // --- Authentication ---

    it('returns 403 for unauthenticated requests', () => {
        cy.request({
            method: 'POST',
            url: '/modules/mcp',
            headers: {'Content-Type': 'application/json'},
            body: {
                jsonrpc: '2.0',
                id: 1,
                method: 'tools/call',
                params: {name: 'executeGraphQL', arguments: {query: '{ currentUser { name } }'}}
            },
            failOnStatusCode: false
        }).its('status').should('be.oneOf', [401, 403]);
    });
});
