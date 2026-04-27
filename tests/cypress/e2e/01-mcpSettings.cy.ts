import {DocumentNode} from 'graphql';

describe('MCP Server — Settings API', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const saveSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSettings.graphql');

    before(() => {
        cy.login();
        cy.apollo({mutation: saveSettings, variables: {whitelist: []}});
    });

    after(() => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: []}});
    });

    it('mcpSettings returns an empty whitelist by default', () => {
        cy.apollo({query: getSettings})
            .its('data.mcpSettings.whitelist')
            .should('deep.equal', []);
    });

    it('mcpSaveSettings saves the whitelist and returns true', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser', 'admin']}})
            .its('data.mcpSaveSettings')
            .should('eq', true);
    });

    it('mcpSettings reads back the saved whitelist entries', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser', 'jcr']}});
        cy.apollo({query: getSettings})
            .its('data.mcpSettings.whitelist')
            .should('deep.equal', ['currentUser', 'jcr']);
    });

    it('mcpSaveSettings with an empty list clears the whitelist', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        cy.apollo({mutation: saveSettings, variables: {whitelist: []}});
        cy.apollo({query: getSettings})
            .its('data.mcpSettings.whitelist')
            .should('deep.equal', []);
    });

    it('mcpSettings preserves dot-path entries verbatim', () => {
        const entries = ['admin.jahia', 'jcr'];
        cy.apollo({mutation: saveSettings, variables: {whitelist: entries}});
        cy.apollo({query: getSettings})
            .its('data.mcpSettings.whitelist')
            .should('deep.equal', entries);
    });
});
