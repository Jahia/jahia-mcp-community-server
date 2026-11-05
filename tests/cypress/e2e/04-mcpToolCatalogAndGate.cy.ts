import {DocumentNode} from 'graphql';
import {createUser, deleteUser} from '@jahia/cypress';
import {
    MCP_URL, TOOL, callTool, callToolAs, createApiToken, deleteApiToken, deleteSkill, getSkills, graphqlAs, listTools,
    saveSkill, setWhitelist, toolPayload
} from '../support/mcp';

// The community module contributes its tools to mcp-server's /modules/mcp endpoint. mcp-server
// admits any authenticated non-guest user, so the community tools enforce their own gate:
// the caller must hold the 'admin' permission on the repository root.
describe('MCP Community tools — catalog and administrator gate', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql');

    const TOKEN_NAME = 'mcp-cypress-catalog-token';
    const EDITOR = 'mcp-community-editor';
    const EDITOR_PASSWORD = 'Password123!';
    const PROBE_SKILL = 'cypress-nonadmin-probe';
    let apiToken: string;

    before(() => {
        cy.login();
        createApiToken(TOKEN_NAME).then(token => {
            apiToken = token;
        });
        createUser(EDITOR, EDITOR_PASSWORD);
        setWhitelist(['query:currentUser']);
        deleteSkill(PROBE_SKILL);
    });

    after(() => {
        deleteSkill(PROBE_SKILL);
        setWhitelist([]);
        deleteUser(EDITOR);
        deleteApiToken(TOKEN_NAME);
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

    // --- The communityMcp GraphQL operations themselves are admin-only ---

    const refused = (body: {data?: {communityMcp?: unknown} | null; errors?: unknown[]}) => {
        const hasErrors = Array.isArray(body.errors) && body.errors.length > 0;
        const noData = !body.data || body.data.communityMcp === null || body.data.communityMcp === undefined;
        expect(hasErrors || noData, JSON.stringify(body)).to.eq(true);
        expect(JSON.stringify(body.data || {}), 'no payload leaked').not.to.match(/hello-jahia|currentUser|"whitelist":\[/);
    };

    it('refuses the communityMcp settings query to a non-administrator', () => {
        graphqlAs(EDITOR, EDITOR_PASSWORD, '{ communityMcp { settings { whitelist } } }').then(response => {
            refused(response.body);
        });
    });

    it('refuses the communityMcp skills query to a non-administrator', () => {
        graphqlAs(EDITOR, EDITOR_PASSWORD, '{ communityMcp { skills { name content } } }').then(response => {
            refused(response.body);
        });
    });

    it('refuses saveSettings to a non-administrator and leaves the whitelist unchanged', () => {
        graphqlAs(EDITOR, EDITOR_PASSWORD, 'mutation { communityMcp { saveSettings(whitelist: ["admin"]) } }').then(response => {
            refused(response.body);
        });
        cy.apollo({query: getSettings}).then(result => {
            expect(result.data.communityMcp.settings.whitelist).to.deep.eq(['query:currentUser']);
        });
    });

    it('refuses saveSkill to a non-administrator and stores nothing', () => {
        graphqlAs(EDITOR, EDITOR_PASSWORD, `mutation { communityMcp { saveSkill(name: "${PROBE_SKILL}", content: "x") } }`).then(response => {
            refused(response.body);
        });
        getSkills().then(result => {
            expect(result.data.communityMcp.skills.map((s: {name: string}) => s.name)).not.to.include(PROBE_SKILL);
        });
    });

    it('refuses deleteSkill to a non-administrator and keeps the skill', () => {
        saveSkill(PROBE_SKILL, 'kept');
        graphqlAs(EDITOR, EDITOR_PASSWORD, `mutation { communityMcp { deleteSkill(name: "${PROBE_SKILL}") } }`).then(response => {
            refused(response.body);
        });
        getSkills().then(result => {
            expect(result.data.communityMcp.skills.map((s: {name: string}) => s.name)).to.include(PROBE_SKILL);
        });
        deleteSkill(PROBE_SKILL);
    });

    it('refuses the communityMcp operations to an unauthenticated caller', () => {
        cy.request({
            method: 'POST',
            url: '/modules/graphql',
            headers: {'Content-Type': 'application/json'},
            body: {query: '{ communityMcp { settings { whitelist } } }'},
            failOnStatusCode: false
        }).then(response => {
            refused(response.body);
        });
    });
});
