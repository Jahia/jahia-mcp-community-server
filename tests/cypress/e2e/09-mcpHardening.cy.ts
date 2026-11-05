import {DocumentNode} from 'graphql';
import {
    TOOL, callTool, callToolWithSessionCookie, createApiToken, deleteApiToken, getApiTokenNames, removeSkill, saveSkill,
    setWhitelist, toolPayload
} from '../support/mcp';

// Guards that hold whatever the whitelist says: the community tools need an explicit Authorization
// header, privilege-granting mutations can never be run, and an oversized response is reported as a
// query to narrow. Each refusal is checked against the server state, not only against the message.
describe('MCP Server — Authentication, privilege and response-size guards', () => {
    /* eslint-disable @typescript-eslint/no-var-requires */
    const getContentsChildren: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getContentsChildren.graphql');
    const deleteNodeByPath: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteNodeByPath.graphql');
    const getAces: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getAces.graphql');
    const grantRoles: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/grantRoles.graphql');
    /* eslint-enable @typescript-eslint/no-var-requires */

    const TOKEN_NAME = 'mcp-cypress-hardening-token';
    const ESCALATION_TOKEN = 'mcp-cypress-escalation-token';
    const PROBE = 'mcp-cypress-hardening-probe';
    const PROBE_PATH = `/sites/systemsite/contents/${PROBE}`;
    const ADD_PROBE = `mutation { jcr { addNode(parentPathOrId: "/sites/systemsite/contents", name: "${PROBE}", ` +
        'primaryNodeType: "jnt:contentFolder") { uuid } } }';
    const PRINCIPAL = 'guest';
    const ROLE = 'editor';
    const NEVER_RUN = 'can never be run';
    const BIG_SKILL = 'cypress-oversized-response';
    const MAX_CONTENT_CHARS = 200000;
    let apiToken: string;

    const probeNodes = () => cy.apollo({query: getContentsChildren, variables: {names: [PROBE]}})
        .then(result => result.data.jcr.nodeByPath.children.nodes.map((n: {name: string}) => n.name));
    const removeProbe = () => cy.apollo({mutation: deleteNodeByPath, variables: {path: PROBE_PATH}, errorPolicy: 'all'});
    /** The ACEs that grant something to PRINCIPAL on the probe node, read as root outside the gate. */
    const probeGrants = () => cy.apollo({
        query: getAces,
        variables: {query: `SELECT * FROM [jnt:ace] WHERE ISDESCENDANTNODE('${PROBE_PATH}') AND [j:principal] = 'u:${PRINCIPAL}'`}
    }).then(result => result.data.jcr.nodesByQuery.nodes.map((n: {path: string}) => n.path));

    const expectRefused = (code: string, fragment?: string) => (response: Cypress.Response<unknown>) => {
        const body = response.body as {result?: {isError?: boolean}};
        expect(body.result?.isError, 'tool error').to.eq(true);
        const error = toolPayload(response as Parameters<typeof toolPayload>[0]).error;
        expect(error.code).to.eq(code);
        if (fragment) {
            expect(error.message).to.include(fragment);
        }
    };

    before(() => {
        cy.login();
        createApiToken(TOKEN_NAME).then(token => {
            apiToken = token;
        });
        deleteApiToken(ESCALATION_TOKEN);
        removeSkill(BIG_SKILL);
    });

    beforeEach(() => {
        cy.login();
        removeProbe();
        setWhitelist([]);
    });

    after(() => {
        removeProbe();
        removeSkill(BIG_SKILL);
        setWhitelist([]);
        deleteApiToken(ESCALATION_TOKEN);
        deleteApiToken(TOKEN_NAME);
    });

    // ── An explicit Authorization header is required ───────────────────────────────────────────
    // A request that a browser sends on its own (a cross-site form post) carries the session cookie
    // but no Authorization header. The community tools refuse it, so a page visited by a logged-in
    // administrator cannot drive them.

    it('refuses a community tool called with the session cookie only', () => {
        callToolWithSessionCookie(TOOL.execute, {query: '{ currentUser { name } }'}).then(expectRefused('forbidden'));
        callToolWithSessionCookie(TOOL.listSkills, {}).then(expectRefused('forbidden'));
    });

    it('accepts the same call with an API token', () => {
        callTool(apiToken, TOOL.execute, {query: '{ currentUser { name } }'}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            expect(toolPayload(response).data.currentUser.name).to.eq('root');
        });
    });

    it('does not run a whitelisted mutation sent with the session cookie only', () => {
        setWhitelist(['mutation:jcr.addNode']);
        callToolWithSessionCookie(TOOL.execute, {query: ADD_PROBE}).then(expectRefused('forbidden'));
        probeNodes().should('deep.equal', []);
        // Control: the same mutation with a token runs, and the read-back sees its node.
        callTool(apiToken, TOOL.execute, {query: ADD_PROBE}).its('body.result.isError').should('eq', false);
        probeNodes().should('deep.equal', [PROBE]);
    });

    // ── Privilege-granting mutations can never be run ──────────────────────────────────────────
    // A broad entry such as mutation:jcr would otherwise let a prompt-injected session grant roles
    // or mint API tokens, which outlive the session.

    it('never grants a role through community.graphql.execute, even with mutation:jcr whitelisted', () => {
        setWhitelist(['mutation:jcr']);
        // The probe node is created through the tool: mutation:jcr is really in force.
        callTool(apiToken, TOOL.execute, {query: ADD_PROBE}).its('body.result.isError').should('eq', false);
        probeNodes().should('deep.equal', [PROBE]);

        callTool(apiToken, TOOL.execute, {
            query: `mutation { jcr { mutateNode(pathOrId: "${PROBE_PATH}") { grantRoles(principalType: USER, ` +
                `principalName: "${PRINCIPAL}", roleNames: ["${ROLE}"]) } } }`
        }).then(expectRefused('forbidden', NEVER_RUN));
        probeGrants().should('deep.equal', []);

        // Control: the same grant, sent as root straight to /modules/graphql, is seen by the read-back.
        cy.apollo({mutation: grantRoles, variables: {path: PROBE_PATH, principalName: PRINCIPAL, roleNames: [ROLE]}})
            .its('data.jcr.mutateNode.grantRoles').should('eq', true);
        probeGrants().should('have.length', 1);
    });

    it('never revokes a role through community.graphql.execute, even with mutation:jcr whitelisted', () => {
        setWhitelist(['mutation:jcr']);
        callTool(apiToken, TOOL.execute, {
            query: 'mutation { jcr { mutateNode(pathOrId: "/sites/systemsite") { revokeRoles(principalType: USER, ' +
                `principalName: "${PRINCIPAL}", roleNames: ["${ROLE}"]) } } }`
        }).then(expectRefused('forbidden', NEVER_RUN));
    });

    it('never creates a personal API token through community.graphql.execute, even with mutation:admin whitelisted', () => {
        setWhitelist(['mutation:admin']);
        callTool(apiToken, TOOL.execute, {
            query: `mutation { admin { personalApiTokens { createToken(name: "${ESCALATION_TOKEN}") } } }`
        }).then(expectRefused('forbidden', NEVER_RUN));
        getApiTokenNames().should('not.include', ESCALATION_TOKEN);
    });

    // ── An oversized response asks for a narrower query ────────────────────────────────────────
    // One skill of the maximum size, read through 40 aliases, gives a response of about 8 MB, over
    // the 5 MB cap. The error is a validation error: retrying the same document cannot succeed.

    it('reports a response over 5 MB as a validation error, and runs the narrowed query', () => {
        saveSkill(BIG_SKILL, 'x'.repeat(MAX_CONTENT_CHARS)).its('data.communityMcp.saveSkill').should('eq', true);
        const aliases = Array.from({length: 40}, (_, i) => `a${i}: communityMcp { skills { content } }`).join(' ');
        callTool(apiToken, TOOL.execute, {query: `{ ${aliases} }`}).then(response => {
            expectRefused('validation')(response);
            const error = toolPayload(response).error;
            expect(error.hint, 'hint').to.be.a('string').and.not.be.empty;
        });
        callTool(apiToken, TOOL.execute, {query: '{ a0: communityMcp { skills { name } } }'})
            .its('body.result.isError').should('eq', false);
    });
});
