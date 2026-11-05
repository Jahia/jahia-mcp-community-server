import {DocumentNode} from 'graphql';
import {expectWhitelistUnchanged, getStoredWhitelist, getWhitelist, setWhitelist, waitForWhitelist} from '../support/mcp';

// Every write is followed by a wait until the server reports the new whitelist back (the
// configuration is applied asynchronously), and every "unchanged" check reads the stored
// configuration too, so no assertion can pass on a value that is merely not applied yet.
describe('MCP Server — Settings API', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const saveSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSettings.graphql');

    const refuseSave = (whitelist: string[], named: string) => {
        cy.apollo({mutation: saveSettings, variables: {whitelist}, errorPolicy: 'all'}).then(result => {
            expect(result.errors, `GraphQL errors for ${JSON.stringify(whitelist)}`).to.have.length.greaterThan(0);
            expect(result.errors[0].message).to.include(named);
        });
    };

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
        getWhitelist().should('deep.equal', []);
        getStoredWhitelist().should('eq', '');
    });

    it('mcpSaveSettings saves the whitelist and returns true', () => {
        // The helper setWhitelist asserts the mutation returns true, then waits for the server to apply it.
        setWhitelist(['currentUser', 'admin']);
    });

    it('mcpSettings reads back the saved whitelist entries', () => {
        setWhitelist(['currentUser', 'jcr']);
        getWhitelist().should('deep.equal', ['currentUser', 'jcr']);
    });

    it('mcpSaveSettings with an empty list clears the whitelist', () => {
        setWhitelist(['currentUser']);
        setWhitelist([]);
        getWhitelist().should('deep.equal', []);
    });

    it('mcpSaveSettings accepts query:/mutation: entries and reads them back verbatim', () => {
        const entries = ['query:jcr.nodeByPath', 'mutation:jcr.deleteNode'];
        cy.apollo({mutation: saveSettings, variables: {whitelist: entries}})
            .its('data.communityMcp.saveSettings').should('eq', true);
        waitForWhitelist(entries);
        getStoredWhitelist().should('eq', entries.join(','));
    });

    it('mcpSaveSettings rejects a malformed entry with an error naming it and keeps the previous whitelist', () => {
        setWhitelist(['query:currentUser']);
        refuseSave(['subscription:jcr'], 'subscription:jcr');
        expectWhitelistUnchanged(['query:currentUser']);
    });

    it('mcpSaveSettings refuses a mutation:communityMcp entry (and sub-paths) and keeps the previous whitelist', () => {
        setWhitelist(['query:currentUser']);
        ['mutation:communityMcp', 'mutation:communityMcp.saveSettings'].forEach(entry => {
            refuseSave([entry], entry);
        });
        expectWhitelistUnchanged(['query:currentUser']);
    });

    it('mcpSaveSettings refuses a mutation:admin.jahia.configuration entry (and sub-paths) and keeps the previous whitelist', () => {
        setWhitelist(['query:currentUser']);
        ['mutation:admin.jahia.configuration', 'mutation:admin.jahia.configuration.value'].forEach(entry => {
            refuseSave(['query:jcr', entry], entry);
        });
        expectWhitelistUnchanged(['query:currentUser']);
    });

    it('mcpSaveSettings accepts mutation:admin, which covers the configuration mutation only on paper', () => {
        // The gate refuses admin.jahia.configuration even under this entry (06-mcpWhitelistEnforcement).
        setWhitelist(['mutation:admin']);
    });

    it('mcpSettings preserves dot-path entries verbatim', () => {
        setWhitelist(['admin.jahia', 'jcr']);
        getWhitelist().should('deep.equal', ['admin.jahia', 'jcr']);
    });
});
