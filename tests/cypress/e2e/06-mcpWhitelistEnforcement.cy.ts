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

    // U4 (e2e mitigation) — a multi-operation document does not let the second (unchecked)
    // operation run. checkAccess's field-path parser reads ONLY the first operation's selection
    // set (op A: currentUser, whitelisted) and forwards the whole document, but executeGraphQL
    // forwards no operationName. graphql-java therefore rejects the multi-op document WHOLESALE
    // before executing anything — either at execution ("Must provide operation name if query
    // contains multiple operations.") or, as here, at document validation (op B references the
    // undefined field admin.jahia.isAlive). Both outcomes yield data: null with a populated
    // errors array, proving NEITHER op A nor op B ran, so op B (admin, non-whitelisted) never
    // reaches its resolver.
    //
    // The fail-closed surfaces as a GraphQL error INSIDE a non-error MCP envelope: the MCP isError
    // flag tracks the HTTP status of the in-process dispatch (McpServlet line 332), and graphql-java
    // returns these errors at HTTP 200, so result.isError is false. The guard therefore asserts on
    // the real evidence that no operation executed (data === null + non-empty errors), NOT on isError.
    // Regression guard for the latent first-op-only parser gap: if operationName forwarding is ever
    // added so an operation could be selected and executed, op A's currentUser data (or op B's admin
    // result/permission error) would appear and data would no longer be null — this test fails loudly.
    it('rejects a multi-operation document so the second (unchecked) operation cannot run', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        executeGraphQL('query A { currentUser { name } } query B { admin { jahia { isAlive } } }')
            .then(response => {
                const payload = JSON.parse(response.body.result.content[0].text);
                // No operation executed: graphql-java rejected the multi-op document wholesale.
                expect(payload.data, 'no operation executed').to.be.null;
                expect(payload.errors, 'document rejected with errors').to.be.an('array').and.not.be.empty;
            });
    });
});
