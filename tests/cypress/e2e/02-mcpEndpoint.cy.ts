import {DocumentNode} from 'graphql';

describe('MCP Server — Endpoint Access Control', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const saveSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createToken: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createToken.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteToken: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteToken.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const listTokens: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/listTokens.graphql');

    const TOKEN_NAME = 'mcp-cypress-test-token';
    let apiToken: string;
    let tokenKey: string;

    before(() => {
        cy.login();
        // Delete all existing tokens to guarantee a clean state
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
        cy.apollo({mutation: saveSettings, variables: {whitelist: []}});
        cy.apollo({mutation: deleteToken, variables: {tokenKey}});
    });

    const callTool = (name: string, args: Record<string, unknown>) => {
        return cy.request({
            method: 'POST',
            url: '/modules/mcp',
            headers: {
                'Content-Type': 'application/json',
                Authorization: `APIToken ${apiToken}`
            },
            body: {
                jsonrpc: '2.0',
                id: 1,
                method: 'tools/call',
                params: {name, arguments: args}
            }
        });
    };

    const executeGraphQL = (query: string) => callTool('community.graphql.execute', {query});

    afterEach(() => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: []}});
    });

    // --- No whitelist ---

    it('allows any operation when whitelist is empty', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: []}});
        executeGraphQL('{ currentUser { name } }')
            .its('body.result.isError').should('eq', false);
    });

    // --- Whitelist enforcement ---

    it('allows a whitelisted top-level operation', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        executeGraphQL('{ currentUser { name } }')
            .its('body.result.isError').should('eq', false);
    });

    it('blocks a non-whitelisted top-level operation', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        executeGraphQL('{ admin { jahia { isAlive } } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('not in the whitelist');
        });
    });

    it('blocks every operation when the whitelist contains only an unrelated entry', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['jcr']}});
        executeGraphQL('{ currentUser { name } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('not in the whitelist');
        });
    });

    // --- Dot-path coverage ---

    it('allows sub-paths when a parent dot-path is whitelisted', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['admin']}});
        executeGraphQL('{ admin { jahia { isAlive } } }')
            .its('body.result.isError').should('eq', false);
    });

    it('allows an exact dot-path match', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['admin.jahia']}});
        executeGraphQL('{ admin { jahia { isAlive } } }')
            .its('body.result.isError').should('eq', false);
    });

    it('blocks a sibling path not covered by the whitelisted dot-path', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['admin.jahia']}});
        executeGraphQL('{ currentUser { name } }').then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('not in the whitelist');
        });
    });

    // --- Introspection always passes ---

    it('allows __schema introspection regardless of whitelist', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        executeGraphQL('{ __schema { types { name } } }')
            .its('body.result.isError').should('eq', false);
    });

    it('allows __type introspection regardless of whitelist', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
        executeGraphQL('{ __type(name: "Query") { fields { name } } }')
            .its('body.result.isError').should('eq', false);
    });

    // --- Operation type: queries and mutations are allowed separately ---

    // A harmless mutation to probe the gate with: it rewrites the whitelist to the same value it
    // already holds, and afterEach resets the whitelist anyway.
    const rewriteWhitelist = (entries: string[]) =>
        `mutation { communityMcp { saveSettings(whitelist: ${JSON.stringify(entries)}) } }`;

    it('a legacy unprefixed entry allows the query but not the same-named mutation', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['communityMcp']}});
        executeGraphQL('{ communityMcp { settings { whitelist } } }')
            .its('body.result.isError').should('eq', false);
        executeGraphQL(rewriteWhitelist(['communityMcp'])).then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('mutation:communityMcp');
        });
    });

    it('query:<path> does not allow the same-named mutation', () => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['query:communityMcp']}});
        executeGraphQL(rewriteWhitelist(['query:communityMcp']))
            .its('body.result.isError').should('eq', true);
    });

    it('mutation:<path> allows exactly that mutation', () => {
        const entries = ['mutation:communityMcp.saveSettings'];
        cy.apollo({mutation: saveSettings, variables: {whitelist: entries}});
        executeGraphQL(rewriteWhitelist(entries)).then(response => {
            expect(response.body.result.isError).to.eq(false);
            expect(JSON.parse(response.body.result.content[0].text).data.communityMcp.saveSettings).to.eq(true);
        });
        // ...and nothing else: the query side of the same namespace stays closed.
        executeGraphQL('{ communityMcp { settings { whitelist } } }')
            .its('body.result.isError').should('eq', true);
    });

    // --- Authentication ---

    it('returns 401 for unauthenticated requests', () => {
        cy.request({
            method: 'POST',
            url: '/modules/mcp',
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
