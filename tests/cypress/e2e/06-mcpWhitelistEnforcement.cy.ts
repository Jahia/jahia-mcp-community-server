import {DocumentNode} from 'graphql';
import {TOOL, callTool, createApiToken, deleteApiToken, setWhitelist} from '../support/mcp';

describe('MCP Server — Whitelist enforcement guards (fail-closed)', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql');

    const TOKEN_NAME = 'mcp-cypress-enforcement-token';
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

    beforeEach(() => {
        setWhitelist([]);
    });

    const executeGraphQL = (query: string) => callTool(apiToken, TOOL.execute, {query});

    // F8 (e2e) — named fragment spreads fail closed when a whitelist is active, proving the
    // containsNamedFragmentSpread block is wired into the live checkAccess path (unit tests
    // only cover the detector). Inline fragments remain allowed.
    it('blocks a named fragment spread when a whitelist is active', () => {
        setWhitelist(['currentUser']);
        executeGraphQL('{ currentUser { name } ...Extra } fragment Extra on Query { admin { jahia { isAlive } } }')
            .then(response => {
                expect(response.body.result.isError).to.eq(true);
                expect(response.body.result.content[0].text).to.include('named fragment');
            });
    });

    it('allows an inline fragment equivalent under the same whitelist', () => {
        setWhitelist(['currentUser']);
        // Inline fragment on the root Query type — valid regardless of installed module types.
        executeGraphQL('{ ... on Query { currentUser { name } } }')
            .its('body.result.isError').should('eq', false);
    });

    // F7(i) — a dangerous mutation that would rewrite the whitelist itself is blocked by the
    // whitelist gate, and the persisted whitelist is unchanged (the mutation never executed).
    it('blocks saveSettings whitelist-rewrite mutation and leaves the whitelist unchanged', () => {
        setWhitelist(['currentUser']);
        executeGraphQL('mutation { communityMcp { saveSettings(whitelist: ["admin"]) } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('can never be run through community.graphql.execute');
        });
        cy.apollo({query: getSettings}).then(result => {
            expect(result.data.communityMcp.settings.whitelist).to.deep.eq(['currentUser']);
        });
    });

    // SEC-364 (was U4) — a multi-operation document is now refused by the GATE, on op B's own
    // merits. Previously checkAccess read only op A's selection set and forwarded the whole
    // document; op B survived solely because executeGraphQL forwards no operationName, so
    // graphql-java rejected the document wholesale (data: null + errors, inside a NON-error MCP
    // envelope). That was a load-bearing accident of the dispatch, not a decision by the gate.
    // The assertion therefore moved from "no operation executed" to "the gate said no".
    it('blocks the second operation of a multi-operation document', () => {
        setWhitelist(['currentUser']);
        executeGraphQL('query A { currentUser { name } } query B { admin { jahia { isAlive } } }')
            .then(response => {
                expect(response.body.result.isError).to.eq(true);
                expect(response.body.result.content[0].text).to.include('not in the whitelist');
                expect(response.body.result.content[0].text).to.include('admin');
            });
    });

    // ── SEC-364 / GHSA-9vrc-45qw-x759 ───────────────────────────────────────────────────────
    // The gate treated a document its extractor could not walk as PERMITTED. Each arm below is
    // valid GraphQL that the old hand-written scanner mis-handled, so the operation the whitelist
    // had just refused executed and returned data.
    //
    // The three-arm shape is deliberate and mirrors the advisory's own proof: the SANITY arm shows
    // the whitelist is loaded and permits what it should, the CONTROL arm shows it genuinely
    // refuses, and only then does the ATTACK arm mean anything. Without the first two, a passing
    // attack arm would be indistinguishable from a gate that blocks everything.

    it('SEC-364 sanity — a whitelisted operation is permitted', () => {
        setWhitelist(['currentUser']);
        executeGraphQL('query { currentUser { name } }')
            .its('body.result.isError').should('eq', false);
    });

    it('SEC-364 control — the same operation is genuinely refused when not whitelisted', () => {
        setWhitelist(['currentUser']);
        executeGraphQL('query { jcr { nodeByPath(path:"/") { name } } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('not in the whitelist');
        });
    });

    it('SEC-364 attack — one leading comma no longer escapes the whitelist', () => {
        setWhitelist(['currentUser']);
        // A comma is an ignored token in the GraphQL grammar; the old skipWS() stopped dead on it,
        // extractFieldPaths returned an empty set, and checkAccess read empty as "permit".
        executeGraphQL('query,{ jcr { nodeByPath(path:"/") { name } } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('not in the whitelist');
        });
    });

    it('SEC-364 attack — a comma before the operation keyword is refused too', () => {
        setWhitelist(['currentUser']);
        executeGraphQL(',query { jcr { nodeByPath(path:"/") { name } } }')
            .its('body.result.isError').should('eq', true);
    });

    it('SEC-364 attack — an unbalanced paren in a string argument no longer hides a sibling', () => {
        setWhitelist(['jcr.nodeByPath']);
        // This is the arm that "treat an empty path set as deny" would NOT have caught: the old
        // scanner extracted {jcr, jcr.nodeByPath} — non-empty, and both whitelisted — because the
        // '(' inside the path argument unbalanced skipBalanced()'s counter, which then ran to
        // end-of-document and never saw the `admin` sibling.
        executeGraphQL('{ jcr { nodeByPath(path: "/x(") { name } } admin { jahia { isAlive } } }')
            .then(response => {
                expect(response.body.result.isError).to.eq(true);
                expect(response.body.result.content[0].text).to.include('admin');
            });
    });

    it('SEC-364 fails closed on a document that cannot be parsed', () => {
        setWhitelist(['currentUser']);
        executeGraphQL('{ jcr { ').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('could not be parsed');
        });
    });

    // False-positive guard: commas are ordinary GraphQL punctuation and must stay legal inside a
    // selection set. A fix that refused them would break every client that formats queries that way.
    it('SEC-364 still permits commas used as ordinary separators', () => {
        setWhitelist(['currentUser']);
        executeGraphQL('{ currentUser { name, alias: name } }')
            .its('body.result.isError').should('eq', false);
    });
});
