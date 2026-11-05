import {createApiToken, deleteApiToken, callTool as callToolRaw, removeSkill, saveSkill, setWhitelist} from '../support/mcp';

describe('MCP Server — Tool gating (whitelist scope per tool)', () => {
    const TOKEN_NAME = 'mcp-cypress-gating-token';
    const GATING_SKILL = 'cypress-gating-skill';
    let apiToken: string;

    before(() => {
        cy.login();
        createApiToken(TOKEN_NAME).then(token => {
            apiToken = token;
        });
        saveSkill(GATING_SKILL, 'gating skill body');
        // A narrow whitelist that covers neither introspection nor skills.
        setWhitelist(['currentUser']);
    });

    // Another spec may have run in between: pin the whitelist this spec relies on.
    beforeEach(() => {
        setWhitelist(['currentUser']);
    });

    after(() => {
        removeSkill(GATING_SKILL);
        setWhitelist([]);
        deleteApiToken(TOKEN_NAME);
    });

    const callTool = (name: string, args: Record<string, unknown>) => callToolRaw(apiToken, name, args);

    // U3 — only community.graphql.execute is whitelist-gated. The introspect and skill tools rely
    // solely on the administrator gate (see 04-mcpToolCatalogAndGate) and ignore the whitelist.
    it('community.graphql.execute IS whitelist-gated (non-whitelisted op blocked)', () => {
        callTool('community.graphql.execute', {query: '{ admin { jahia { isAlive } } }'}).then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('not in the whitelist');
        });
    });

    it('community.graphql.introspect is NOT whitelist-gated', () => {
        callTool('community.graphql.introspect', {}).its('body.result.isError').should('eq', false);
    });

    it('community.skill.list is NOT whitelist-gated', () => {
        callTool('community.skill.list', {}).its('body.result.isError').should('eq', false);
    });

    it('community.skill.get is NOT whitelist-gated', () => {
        callTool('community.skill.get', {name: GATING_SKILL})
            .its('body.result.isError').should('eq', false);
    });

    // The introspect tool is bounded: with no arguments it returns the type list plus
    // the root Query/Mutation details only (one __Type.fields per request, so Jahia's bad-faith
    // introspection guard never trips); specific types are requested through typeNames.
    it('community.graphql.introspect without arguments returns the type list and the root types', () => {
        callTool('community.graphql.introspect', {}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            const payload = JSON.parse(response.body.result.content[0].text);
            expect(payload.schema.queryType.name).to.eq('Query');
            expect(payload.schema.mutationType.name).to.eq('Mutation');
            expect(payload.schema.types).to.be.an('array').and.have.length.greaterThan(10);
            expect(Object.keys(payload.types)).to.have.members(['Query', 'Mutation']);
            expect(payload.types.Query.fields.map((f: {name: string}) => f.name)).to.include('jcr');
            expect(payload.skippedTypes).to.deep.eq([]);
            expect(payload.truncatedTypes).to.deep.eq([]);
            // Bounded: the whole default answer stays far below the 100k-character budget.
            expect(response.body.result.content[0].text.length).to.be.lessThan(100000);
        });
    });

    it('community.graphql.introspect describes the types named in typeNames', () => {
        callTool('community.graphql.introspect', {typeNames: ['JCRNode', 'NoSuchType']}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            const payload = JSON.parse(response.body.result.content[0].text);
            expect(payload.schema).to.be.undefined;
            expect(payload.types.JCRNode.fields.map((f: {name: string}) => f.name)).to.include('path');
            expect(payload.skippedTypes).to.deep.eq(['NoSuchType']);
        });
    });

    it('community.graphql.introspect refuses more than 25 typeNames', () => {
        const names = Array.from({length: 26}, (_, i) => `Type${i}`);
        callTool('community.graphql.introspect', {typeNames: names}).then(response => {
            // Rejected either by mcp-server's schema validation (JSON-RPC error or isError result)
            // or by the tool's own check — in every case nothing is introspected.
            const rejected = Boolean(response.body.error) || response.body.result.isError === true;
            expect(rejected).to.eq(true);
        });
    });

    // U2 — Stage-7 candidate (benign/latent SYSTEM-session finding).
    // The skill tools run under a SYSTEM JCR session (doExecuteWithSystemSession), so they
    // ignore BOTH the whitelist and the caller's per-node ACLs. This test can only pin
    // "reads succeed despite the whitelist"; it CANNOT demonstrate an exploitable ACL bypass,
    // because the community tools are restricted to server administrators (every valid caller is
    // already an admin with broad ACLs) and no restrictive ACL is set on the seeded skills. The bypass
    // becomes real only if the grant is broadened to non-admins or a deny-ACL is added later.
    it('skill tools succeed via SYSTEM session despite a whitelist that excludes them', () => {
        callTool('community.skill.list', {}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            const {skills} = JSON.parse(response.body.result.content[0].text);
            expect(skills.map((s: {name: string}) => s.name)).to.include(GATING_SKILL);
        });
        callTool('community.skill.get', {name: GATING_SKILL}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            expect(response.body.result.content[0].text).to.include('gating skill body');
        });
    });
});
