import {DocumentNode} from 'graphql';

describe('MCP Server — Whitelist enforcement guards (fail-closed)', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const saveSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createToken: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createToken.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteToken: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteToken.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const listTokens: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/listTokens.graphql');

    const TOKEN_NAME = 'mcp-cypress-enforcement-token';
    let apiToken: string;
    let tokenKey: string;

    before(() => {
        cy.login();
        cy.apollo({query: listTokens}).then(result => {
            const nodes: {key: string}[] = result.data.admin.personalApiTokens.tokens.nodes;
            nodes.forEach(t => {
                cy.apollo({mutation: deleteToken, variables: {tokenKey: t.key}});
            });
        });
        cy.apollo({mutation: createToken, variables: {tokenName: TOKEN_NAME, scopes: ['graphql', 'community-mcp']}}).then(result => {
            apiToken = result.data.admin.personalApiTokens.createToken;
        });
        cy.apollo({query: listTokens}).then(result => {
            const found = result.data.admin.personalApiTokens.tokens.nodes.find(
                (t: {key: string; name: string}) => t.name === TOKEN_NAME
            );
            tokenKey = found.key;
        });
    });

    after(() => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: []}});
        cy.apollo({mutation: deleteToken, variables: {tokenKey}});
    });

    afterEach(() => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: []}});
    });

    const executeGraphQL = (query: string) => cy.request({
        method: 'POST',
        url: '/modules/community-mcp',
        headers: {'Content-Type': 'application/json', Authorization: `APIToken ${apiToken}`},
        body: {jsonrpc: '2.0', id: 1, method: 'tools/call', params: {name: 'executeGraphQL', arguments: {query}}}
    });

    // F8 (e2e) — named fragment spreads fail closed when a whitelist is active, proving the
    // containsNamedFragmentSpread block is wired into the live checkAccess path (unit tests
    // only cover the detector). Inline fragments remain allowed.
    it('blocks a named fragment spread when a whitelist is active', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        executeGraphQL('{ currentUser { name } ...Extra } fragment Extra on Query { admin { jahia { isAlive } } }')
            .then(response => {
                expect(response.body.result.isError).to.eq(true);
                expect(response.body.result.content[0].text).to.include('named fragment');
            });
    });

    it('allows an inline fragment equivalent under the same whitelist', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        // Inline fragment on the root Query type — valid regardless of installed module types.
        executeGraphQL('{ ... on Query { currentUser { name } } }')
            .its('body.result.isError').should('eq', false);
    });

    // F7(i) — a dangerous mutation that would rewrite the whitelist itself is blocked by the
    // whitelist gate, and the persisted whitelist is unchanged (the mutation never executed).
    it('blocks saveSettings whitelist-rewrite mutation and leaves the whitelist unchanged', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        executeGraphQL('mutation { mcp { saveSettings(whitelist: ["admin"]) } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('not in the whitelist');
        });
        cy.apollo({query: getSettings}).then(result => {
            expect(result.data.mcp.settings.whitelist).to.deep.eq(['currentUser']);
        });
    });

    // U4 (e2e mitigation) — a multi-operation document is rejected end-to-end. checkAccess sees
    // only op A (currentUser, allowed), but executeGraphQL forwards no operationName, so
    // graphql-java refuses the multi-op document and op B (admin) never executes. This is the
    // regression guard for the latent first-op-only parser gap: if operationName forwarding is
    // ever added, op B would evade the whitelist and this assertion must be re-examined.
    it('rejects a multi-operation document so the second (unchecked) operation cannot run', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        executeGraphQL('query A { currentUser { name } } query B { admin { jahia { isAlive } } }')
            .its('body.result.isError').should('eq', true);
    });
});
