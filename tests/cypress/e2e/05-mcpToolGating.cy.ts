import {DocumentNode} from 'graphql';

describe('MCP Server — Tool gating (whitelist scope per tool)', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const saveSettings: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSettings.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createToken: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createToken.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteToken: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteToken.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const listTokens: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/listTokens.graphql');

    const TOKEN_NAME = 'mcp-cypress-gating-token';
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
        // A narrow whitelist that covers neither introspection nor skills.
        cy.apollo({mutation: saveSettings, variables: {whitelist: ['currentUser']}});
    });

    after(() => {
        cy.apollo({mutation: saveSettings, variables: {whitelist: []}});
        cy.apollo({mutation: deleteToken, variables: {tokenKey}});
    });

    const callTool = (name: string, args: Record<string, unknown>) => cy.request({
        method: 'POST',
        url: '/modules/community-mcp',
        headers: {'Content-Type': 'application/json', Authorization: `APIToken ${apiToken}`},
        body: {jsonrpc: '2.0', id: 1, method: 'tools/call', params: {name, arguments: args}}
    });

    // U3 — only executeGraphQL is whitelist-gated. introspectSchema/listSkills/getSkill rely
    // solely on the endpoint permission check and are NOT subject to the whitelist.
    it('executeGraphQL IS whitelist-gated (non-whitelisted op blocked)', () => {
        callTool('executeGraphQL', {query: '{ admin { jahia { isAlive } } }'}).then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('not in the whitelist');
        });
    });

    it('introspectSchema is NOT whitelist-gated', () => {
        callTool('introspectSchema', {}).its('body.result.isError').should('eq', false);
    });

    it('listSkills is NOT whitelist-gated', () => {
        callTool('listSkills', {}).its('body.result.isError').should('eq', false);
    });

    it('getSkill is NOT whitelist-gated', () => {
        callTool('getSkill', {name: 'default/hello-jahia'})
            .its('body.result.isError').should('eq', false);
    });

    // F2 — introspectSchema runs the two-step __schema / per-type __type workaround without
    // tripping Jahia's bad-faith introspection guard, ignores the whitelist, and returns a
    // parsed schema picture containing query + mutation type names and per-type field details.
    it('introspectSchema returns a schema with query/mutation types and per-type details', () => {
        callTool('introspectSchema', {}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            const payload = JSON.parse(response.body.result.content[0].text);
            expect(payload.schema).to.exist;
            expect(payload.schema.queryType.name).to.be.a('string');
            expect(payload.schema.mutationType.name).to.be.a('string');
            expect(payload.schema.types).to.be.an('array').and.have.length.greaterThan(0);
            // Step-2 per-type detail map (keyed by type name) is populated.
            expect(payload.types).to.be.an('object');
            expect(Object.keys(payload.types).length).to.be.greaterThan(0);
        });
    });

    // U2 — Stage-7 candidate (benign/latent SYSTEM-session finding).
    // listSkills/getSkill run under a SYSTEM JCR session (doExecuteWithSystemSession), so they
    // ignore BOTH the whitelist and the caller's per-node ACLs. This test can only pin
    // "reads succeed despite the whitelist"; it CANNOT demonstrate an exploitable ACL bypass,
    // because community-mcp is granted only to admin-role users (every valid caller is already
    // an admin with broad ACLs) and no restrictive ACL is set on the seeded skills. The bypass
    // becomes real only if the grant is broadened to non-admins or a deny-ACL is added later.
    // See 05-implementation-report.md → Stage-7 handoff.
    it('listSkills/getSkill succeed via SYSTEM session despite a whitelist that excludes them', () => {
        callTool('listSkills', {}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            const skills = JSON.parse(response.body.result.content[0].text);
            expect(skills.map((s: {name: string}) => s.name)).to.include('hello-jahia');
        });
        callTool('getSkill', {name: 'default/hello-jahia'}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            expect(response.body.result.content[0].text).to.include('Hello Jahian');
        });
    });
});
