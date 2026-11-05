import {DocumentNode} from 'graphql';
import {MCP_URL, TOOL, callTool, createApiToken, deleteApiToken, setWhitelist, toolText} from '../support/mcp';

describe('MCP Server — Endpoint Access Control', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql');
    const TOKEN_NAME = 'mcp-cypress-test-token';
    let apiToken: string;

    before(() => {
        cy.login();
        createApiToken(TOKEN_NAME).then(token => {
            apiToken = token;
        });
    });

    after(() => {
        setWhitelist([]);
        deleteApiToken(TOKEN_NAME);
    });

    // Every test sets the whitelist it needs and starts from the read-only default otherwise.
    beforeEach(() => {
        setWhitelist([]);
    });

    const executeGraphQL = (query: string) => callTool(apiToken, TOOL.execute, {query});

    // A harmless mutation to probe the gate with: jcr.mutateNode on the root with no sub-mutation
    // selected changes nothing.
    const PROBE_MUTATION = 'mutation { jcr { mutateNode(pathOrId: "/") { node { path } } } }';
    const PROBE_ENTRY = 'mutation:jcr.mutateNode';

    // --- Read-only default (empty whitelist) ---

    it('allows every query when the whitelist is empty', () => {
        executeGraphQL('{ currentUser { name } }').its('body.result.isError').should('eq', false);
        executeGraphQL('{ admin { jahia { isAlive } } }').its('body.result.isError').should('eq', false);
    });

    it('denies every mutation when the whitelist is empty (read-only default)', () => {
        executeGraphQL(PROBE_MUTATION).then(response => {
            expect(response.body.result.isError).to.eq(true);
            const text = toolText(response);
            expect(text).to.include('mutations require an explicit mutation:<path> whitelist entry');
            expect(text).to.include('the whitelist is empty, which allows queries only');
        });
    });

    // --- Whitelist enforcement ---

    it('allows a whitelisted top-level operation', () => {
        setWhitelist(['currentUser']);
        executeGraphQL('{ currentUser { name } }')
            .its('body.result.isError').should('eq', false);
    });

    it('blocks a non-whitelisted top-level operation', () => {
        setWhitelist(['currentUser']);
        executeGraphQL('{ admin { jahia { isAlive } } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(toolText(response)).to.include('not in the whitelist');
        });
    });

    it('blocks every operation when the whitelist contains only an unrelated entry', () => {
        setWhitelist(['jcr']);
        executeGraphQL('{ currentUser { name } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(toolText(response)).to.include('not in the whitelist');
        });
    });

    // --- Dot-path coverage ---

    it('allows sub-paths when a parent dot-path is whitelisted', () => {
        setWhitelist(['admin']);
        executeGraphQL('{ admin { jahia { isAlive } } }')
            .its('body.result.isError').should('eq', false);
    });

    it('allows an exact dot-path match', () => {
        setWhitelist(['admin.jahia']);
        executeGraphQL('{ admin { jahia { isAlive } } }')
            .its('body.result.isError').should('eq', false);
    });

    it('blocks a sibling path not covered by the whitelisted dot-path', () => {
        setWhitelist(['admin.jahia']);
        executeGraphQL('{ currentUser { name } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(toolText(response)).to.include('not in the whitelist');
        });
    });

    // --- Introspection always passes ---

    it('allows __schema introspection regardless of whitelist', () => {
        setWhitelist(['currentUser']);
        executeGraphQL('{ __schema { types { name } } }')
            .its('body.result.isError').should('eq', false);
    });

    it('allows __type introspection regardless of whitelist', () => {
        setWhitelist(['currentUser']);
        executeGraphQL('{ __type(name: "Query") { fields { name } } }')
            .its('body.result.isError').should('eq', false);
    });

    // --- Operation type: queries and mutations are allowed separately ---

    it('a legacy unprefixed entry allows the query but not the same-named mutation', () => {
        setWhitelist(['jcr']);
        executeGraphQL('{ jcr { nodeByPath(path: "/") { path } } }')
            .its('body.result.isError').should('eq', false);
        executeGraphQL(PROBE_MUTATION).then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(toolText(response)).to.include('mutation:jcr');
        });
    });

    it('query:<path> does not allow the same-named mutation', () => {
        setWhitelist(['query:jcr']);
        executeGraphQL(PROBE_MUTATION).its('body.result.isError').should('eq', true);
    });

    it('mutation:<path> allows exactly that mutation and nothing on the query side', () => {
        setWhitelist([PROBE_ENTRY]);
        executeGraphQL(PROBE_MUTATION).then(response => {
            expect(response.body.result.isError).to.eq(false);
            expect(JSON.parse(toolText(response)).data.jcr.mutateNode.node.path).to.eq('/');
        });
        // The whitelist is no longer empty, so queries are no longer open by default.
        executeGraphQL('{ jcr { nodeByPath(path: "/") { path } } }')
            .its('body.result.isError').should('eq', true);
    });

    it('subscriptions are never allowed', () => {
        executeGraphQL('subscription { currentUser { name } }')
            .its('body.result.isError').should('eq', true);
    });

    // --- mutation:communityMcp is always denied ---

    it('never runs a communityMcp mutation, whatever the whitelist holds', () => {
        const mutation = 'mutation { communityMcp { saveSettings(whitelist: []) } }';
        [[], ['communityMcp'], ['query:communityMcp'], ['mutation:jcr']].forEach(entries => {
            setWhitelist(entries);
            executeGraphQL(mutation).then(response => {
                expect(response.body.result.isError, JSON.stringify(entries)).to.eq(true);
                expect(toolText(response), JSON.stringify(entries)).to.include('can never be run through community.graphql.execute');
                expect(toolText(response), JSON.stringify(entries)).to.include('\'mutation:communityMcp');
            });
        });
    });

    it('never lets a community mutation rewrite the whitelist: it stays unchanged', () => {
        setWhitelist(['currentUser']);
        executeGraphQL('mutation { communityMcp { saveSettings(whitelist: ["admin"]) } }')
            .its('body.result.isError').should('eq', true);
        cy.apollo({query: getSettings}).then(result => {
            expect(result.data.communityMcp.settings.whitelist).to.deep.eq(['currentUser']);
        });
    });

    // --- Authentication ---

    it('returns 401 for unauthenticated requests', () => {
        cy.request({
            method: 'POST',
            url: MCP_URL,
            headers: {'Content-Type': 'application/json'},
            body: {
                jsonrpc: '2.0',
                id: 1,
                method: 'tools/call',
                params: {name: 'community.graphql.execute', arguments: {query: '{ currentUser { name } }'}}
            },
            failOnStatusCode: false
        }).its('status').should('eq', 401);
    });
});
