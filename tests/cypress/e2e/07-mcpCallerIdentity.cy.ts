import {DocumentNode} from 'graphql';
import {TOOL, callTool, toolPayload} from '../support/mcp';

describe('MCP Community tools — forwarded caller identity', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const saveSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createToken: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createToken.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteToken: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteToken.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const listTokens: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/listTokens.graphql');

    const TOKEN_NAME = 'mcp-cypress-bodycap-token';
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
        cy.apollo({mutation: createToken, variables: {tokenName: TOKEN_NAME}}).then(result => {
            apiToken = result.data.admin.personalApiTokens.createToken;
        });
        cy.apollo({query: listTokens}).then(result => {
            const found = result.data.admin.personalApiTokens.tokens.nodes.find(
                (t: {key: string; name: string}) => t.name === TOKEN_NAME
            );
            tokenKey = found.key;
        });
        cy.apollo({mutation: saveSettings, variables: {whitelist: []}});
    });

    after(() => {
        cy.apollo({mutation: deleteToken, variables: {tokenKey}});
    });

    // F17 — in-process GraphQL dispatch runs under the CALLER's forwarded identity, not an
    // escalated system/anonymous session. With allow-all (empty whitelist), currentUser resolves
    // to the token user (the admin who created the token), proving identity forwarding.
    //
    // The denial branch (an authenticated caller ACL-denied on the target) is unprovisionable:
    // the community tools are restricted to server administrators, who already hold broad ACLs.
    // This is therefore a characterization of the positive (forwarded-identity) path only.
    it('community.graphql.execute runs as the forwarded caller (currentUser resolves to the token user)', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: []}});
        callTool(apiToken, TOOL.execute, {query: '{ currentUser { name } }'}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            const payload = toolPayload(response);
            // The token was created by the logged-in root user: GraphQL must resolve exactly that
            // identity (not guest, not a system session). mcp-server binds it on the worker thread.
            expect(payload.data.currentUser.name).to.eq('root');
        });
    });
});
