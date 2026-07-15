import {DocumentNode} from 'graphql';

describe('MCP Server — Health-check GET endpoint', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createToken: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createToken.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteToken: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteToken.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const listTokens: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/listTokens.graphql');

    const TOKEN_NAME = 'mcp-cypress-health-token';
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
        cy.apollo({mutation: deleteToken, variables: {tokenKey}});
    });

    // F16 / D2 — authenticated GET returns the health-check payload.
    it('returns 200 with the health-check JSON and the four tool names', () => {
        cy.request({
            method: 'GET',
            url: '/modules/community-mcp',
            headers: {Authorization: `APIToken ${apiToken}`}
        }).then(response => {
            expect(response.status).to.eq(200);
            expect(response.body.status).to.eq('Jahia MCP server running');
            expect(response.body.tools).to.deep.eq([
                'executeGraphQL', 'introspectSchema', 'listSkills', 'getSkill'
            ]);
        });
    });

    // U6 — version is a hardcoded "1.0.0" literal (NOT derived from the pom version).
    // Characterization + canary: when the version is later derived from the bundle, this
    // assertion will fail and prompt an update.
    it('reports the hardcoded version "1.0.0"', () => {
        cy.request({
            method: 'GET',
            url: '/modules/community-mcp',
            headers: {Authorization: `APIToken ${apiToken}`}
        }).its('body.version').should('eq', '1.0.0');
    });

    // D2 — GET shares the single community-mcp gate with POST; unauthenticated → 401.
    it('returns 401 with a WWW-Authenticate realm for an unauthenticated GET', () => {
        cy.request({
            method: 'GET',
            url: '/modules/community-mcp',
            failOnStatusCode: false
        }).then(response => {
            expect(response.status).to.eq(401);
            expect(response.headers['www-authenticate']).to.contain('realm="community-mcp"');
        });
    });
});
