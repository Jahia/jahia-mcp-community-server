import {DocumentNode} from 'graphql';
import {setWhitelist} from '../support/mcp';

describe('MCP Server — Settings API', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const saveSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSettings.graphql');

    before(() => {
        cy.login();
    });

    // Each test starts from, and leaves behind, the read-only default (empty whitelist).
    beforeEach(() => {
        setWhitelist([]);
    });

    after(() => {
        setWhitelist([]);
    });

    it('mcpSettings returns the empty (read-only default) whitelist after a reset', () => {
        cy.apollo({query: getSettings})
            .its('data.communityMcp.settings.whitelist')
            .should('deep.equal', []);
    });

    it('mcpSaveSettings saves the whitelist and returns true', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser', 'admin']}})
            .its('data.communityMcp.saveSettings')
            .should('eq', true);
    });

    it('mcpSettings reads back the saved whitelist entries', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser', 'jcr']}});
        cy.apollo({query: getSettings})
            .its('data.communityMcp.settings.whitelist')
            .should('deep.equal', ['currentUser', 'jcr']);
    });

    it('mcpSaveSettings with an empty list clears the whitelist', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        cy.apollo({mutation: saveSettings, variables: {whitelist: []}});
        cy.apollo({query: getSettings})
            .its('data.communityMcp.settings.whitelist')
            .should('deep.equal', []);
    });

    it('mcpSaveSettings accepts query:/mutation: entries and reads them back verbatim', () => {
        const entries = ['query:jcr.nodeByPath', 'mutation:jcr.deleteNode'];
        cy.apollo({mutation: saveSettings, variables: {whitelist: entries}})
            .its('data.communityMcp.saveSettings').should('eq', true);
        cy.apollo({query: getSettings})
            .its('data.communityMcp.settings.whitelist')
            .should('deep.equal', entries);
    });

    it('mcpSaveSettings rejects a malformed entry with an error naming it and keeps the previous whitelist', () => {
        setWhitelist(['query:currentUser']);
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['subscription:jcr']}, errorPolicy: 'all'}).then(result => {
            expect(result.errors, 'GraphQL errors').to.have.length.greaterThan(0);
            expect(result.errors[0].message).to.include('subscription:jcr');
        });
        cy.apollo({query: getSettings})
            .its('data.communityMcp.settings.whitelist')
            .should('deep.equal', ['query:currentUser']);
    });

    it('mcpSaveSettings refuses a mutation:communityMcp entry (and sub-paths) and keeps the previous whitelist', () => {
        setWhitelist(['query:currentUser']);
        ['mutation:communityMcp', 'mutation:communityMcp.saveSettings'].forEach(entry => {
            cy.apollo({mutation: saveSettings, variables: {whitelist: [entry]}, errorPolicy: 'all'}).then(result => {
                expect(result.errors, `errors for ${entry}`).to.have.length.greaterThan(0);
                expect(result.errors[0].message).to.include(entry);
            });
        });
        cy.apollo({query: getSettings})
            .its('data.communityMcp.settings.whitelist')
            .should('deep.equal', ['query:currentUser']);
    });

    it('mcpSettings preserves dot-path entries verbatim', () => {
        const entries = ['admin.jahia', 'jcr'];
        cy.apollo({mutation: saveSettings, variables: {whitelist: entries}});
        cy.apollo({query: getSettings})
            .its('data.communityMcp.settings.whitelist')
            .should('deep.equal', entries);
    });
});
