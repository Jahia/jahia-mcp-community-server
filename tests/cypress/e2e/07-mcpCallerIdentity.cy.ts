import {TOOL, callTool, createApiToken, deleteApiToken, setWhitelist, toolPayload} from '../support/mcp';

describe('MCP Community tools — forwarded caller identity', () => {
    const TOKEN_NAME = 'mcp-cypress-bodycap-token';
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

    // F17 — in-process GraphQL dispatch runs under the CALLER's forwarded identity, not an
    // escalated system/anonymous session. With the read-only default (empty whitelist, queries allowed), currentUser resolves
    // to the token user (the admin who created the token), proving identity forwarding.
    //
    // The denial branch (an authenticated caller ACL-denied on the target) is unprovisionable:
    // the community tools are restricted to server administrators, who already hold broad ACLs.
    // This is therefore a characterization of the positive (forwarded-identity) path only.
    it('community.graphql.execute runs as the forwarded caller (currentUser resolves to the token user)', () => {
        setWhitelist([]);
        callTool(apiToken, TOOL.execute, {query: '{ currentUser { name } }'}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            const payload = toolPayload(response);
            // The token was created by the logged-in root user: GraphQL must resolve exactly that
            // identity (not guest, not a system session). mcp-server binds it on the worker thread.
            expect(payload.data.currentUser.name).to.eq('root');
        });
    });
});
