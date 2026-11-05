import {DocumentNode} from 'graphql';
import {TOOL, callTool, createApiToken, deleteApiToken, expectWhitelistUnchanged, setWhitelist, toolPayload} from '../support/mcp';

describe('MCP Server — Whitelist enforcement guards (fail-closed)', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getContentsChildren: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getContentsChildren.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteNodeByPath: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteNodeByPath.graphql');

    const PROBE = 'mcp-cypress-spread-probe';
    const PROBE_PATH = `/sites/systemsite/contents/${PROBE}`;
    const ADD_PROBE = `jcr { addNode(parentPathOrId: "/sites/systemsite/contents", name: "${PROBE}", primaryNodeType: "jnt:contentFolder") { uuid } }`;
    const NAMED_SPREAD_DENIAL = 'Operation not allowed: named fragment spreads are not permitted ' +
        '(use inline fragments \'... on Type { ... }\' instead)';
    const CONFIGURATION_DENIAL = 'Operation not allowed: \'mutation:admin.jahia.configuration\' ' +
        'can never be run through community.graphql.execute';

    /** The probe node names found under /sites/systemsite/contents, read as root outside the gate. */
    const probeNodes = () => cy.apollo({query: getContentsChildren, variables: {names: [PROBE]}})
        .then(result => result.data.jcr.nodeByPath.children.nodes.map((n: {name: string}) => n.name));
    const removeProbe = () => cy.apollo({mutation: deleteNodeByPath, variables: {path: PROBE_PATH}, errorPolicy: 'all'});

    const TOKEN_NAME = 'mcp-cypress-enforcement-token';
    let apiToken: string;

    before(() => {
        cy.login();
        createApiToken(TOKEN_NAME).then(token => {
            apiToken = token;
        });
    });

    after(() => {
        removeProbe();
        setWhitelist([]);
        deleteApiToken(TOKEN_NAME);
    });

    beforeEach(() => {
        removeProbe();
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
        expectWhitelistUnchanged(['currentUser']);
    });

    // ── Named fragment spreads: refused in EVERY state, the read-only default included ─────────
    // The gate does not expand a spread, so `mutation { ...F }` has no path it could check. The
    // control arm shows that the probe mutation really runs, and is really seen by the read-back,
    // when it is allowed and written inline: only then does "no probe node" after the spread mean
    // that the gate stopped it.

    it('named spread control — the inline probe mutation runs when it is whitelisted', () => {
        setWhitelist(['mutation:jcr.addNode']);
        executeGraphQL(`mutation { ${ADD_PROBE} }`).its('body.result.isError').should('eq', false);
        probeNodes().should('deep.equal', [PROBE]);
    });

    [[], ['mutation:jcr.addNode']].forEach(entries => {
        it(`refuses a mutation hidden in a named fragment spread, and does not run it (whitelist ${JSON.stringify(entries)})`, () => {
            setWhitelist(entries);
            executeGraphQL(`mutation { ...F } fragment F on Mutation { ${ADD_PROBE} }`).then(response => {
                expect(response.body.result.isError).to.eq(true);
                expect(toolPayload(response).error.code).to.eq('forbidden');
                expect(toolPayload(response).error.message).to.eq(NAMED_SPREAD_DENIAL);
            });
            probeNodes().should('deep.equal', []);
        });
    });

    it('refuses a query that uses a named fragment spread under the read-only default', () => {
        // The same selection, inline, is allowed: only the spread is refused.
        executeGraphQL('{ ... on Query { currentUser { name } } }').its('body.result.isError').should('eq', false);
        executeGraphQL('{ ...Q } fragment Q on Query { currentUser { name } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(toolPayload(response).error.message).to.eq(NAMED_SPREAD_DENIAL);
        });
    });

    // ── admin.jahia.configuration: never runnable, even under a broader mutation:admin entry ──
    // It could rewrite this module's own configuration (PID org.jahia.community.mcp). The stored
    // configuration is read back afterwards: the mutation must not have run.

    it('never runs mutation:admin.jahia.configuration, even with mutation:admin whitelisted', () => {
        setWhitelist(['mutation:admin']);
        executeGraphQL('mutation { admin { jahia { configuration(pid: "org.jahia.community.mcp") ' +
            '{ value(name: "whitelist", value: "mutation:communityMcp") } } } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(toolPayload(response).error.code).to.eq('forbidden');
            expect(toolPayload(response).error.message).to.eq(CONFIGURATION_DENIAL);
        });
        expectWhitelistUnchanged(['mutation:admin']);
    });

    // SEC-364 — a multi-operation document is refused by the GATE, on op B's own merits: the gate
    // extracts the paths of every operation in the document. The assertion is "the gate said no",
    // not "no operation executed": community.graphql.execute forwards no operationName, so
    // graphql-java would reject the document anyway, and the test must not depend on that.
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
    // The gate parses the document with graphql-java's parser and walks the AST of every operation;
    // a document that does not parse is denied. Each arm below is valid GraphQL with tokens a naive
    // scanner could mis-read (commas, parentheses inside string arguments), and must be policed.
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
        // A comma is an ignored token in the GraphQL grammar; the parser skips it, so the jcr
        // selection is extracted and refused.
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
        // The '(' is inside a string value, so the parser treats it as data: the gate extracts both
        // jcr.nodeByPath (whitelisted) and the `admin` sibling (not whitelisted), and refuses.
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
