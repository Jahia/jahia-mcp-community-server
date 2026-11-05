import {DocumentNode} from 'graphql';
import {createUser, deleteUser} from '@jahia/cypress';
import {MCP_URL, TOOL, callTool, callToolAs, listTools, toolPayload} from '../support/mcp';

// The community module contributes its tools to mcp-server's /modules/mcp endpoint. mcp-server
// admits any authenticated non-guest user, so the community tools enforce their own gate:
// the caller must hold the 'admin' permission on the repository root.
describe('MCP Community tools — catalog and administrator gate', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createToken: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createToken.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteToken: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteToken.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const listTokens: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/listTokens.graphql');

    const TOKEN_NAME = 'mcp-cypress-catalog-token';
    const EDITOR = 'mcp-community-editor';
    const EDITOR_PASSWORD = 'Password123!';
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
        createUser(EDITOR, EDITOR_PASSWORD);
    });

    after(() => {
        deleteUser(EDITOR);
        cy.apollo({mutation: deleteToken, variables: {tokenKey}});
    });

    it('lists the four community tools alongside the mcp-server catalog', () => {
        listTools(apiToken).then(response => {
            expect(response.status).to.eq(200);
            const names: string[] = response.body.result.tools.map((t: {name: string}) => t.name);
            expect(names).to.include.members([TOOL.execute, TOOL.introspect, TOOL.listSkills, TOOL.getSkill]);
        });
    });

    it('announces community.graphql.execute as destructive and the others as read-only', () => {
        listTools(apiToken).then(response => {
            const byName = (name: string) => response.body.result.tools.find((t: {name: string}) => t.name === name);
            expect(byName(TOOL.execute).annotations.destructiveHint).to.eq(true);
            [TOOL.introspect, TOOL.listSkills, TOOL.getSkill].forEach(name => {
                expect(byName(name).annotations.readOnlyHint, name).to.eq(true);
            });
        });
    });

    it('lets an administrator call a community tool', () => {
        callTool(apiToken, TOOL.execute, {query: '{ currentUser { name } }'})
            .its('body.result.isError').should('eq', false);
    });

    [
        {name: TOOL.execute, args: {query: '{ currentUser { name } }'}},
        {name: TOOL.introspect, args: {}},
        {name: TOOL.listSkills, args: {}},
        {name: TOOL.getSkill, args: {name: 'default/hello-jahia'}}
    ].forEach(({name, args}) => {
        it(`refuses ${name} to a non-administrator with a forbidden error`, () => {
            callToolAs(EDITOR, EDITOR_PASSWORD, name, args).then(response => {
                expect(response.status).to.eq(200);
                expect(response.body.result.isError).to.eq(true);
                expect(toolPayload(response).error.code).to.eq('forbidden');
            });
        });
    });

    it('rejects an unauthenticated tools/call with 401 (mcp-server authentication)', () => {
        cy.request({
            method: 'POST',
            url: MCP_URL,
            headers: {'Content-Type': 'application/json'},
            body: {jsonrpc: '2.0', id: 1, method: 'tools/call', params: {name: TOOL.listSkills, arguments: {}}},
            failOnStatusCode: false
        }).its('status').should('eq', 401);
    });
});
