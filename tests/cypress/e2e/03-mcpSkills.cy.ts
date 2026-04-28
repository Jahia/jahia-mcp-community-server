import {DocumentNode} from 'graphql';

describe('MCP Server — Skills', () => {
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSkills: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSkills.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const saveSkill: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSkill.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteSkill: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteSkill.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const createToken: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/createToken.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const deleteToken: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteToken.graphql');
    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const listTokens: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/listTokens.graphql');

    const TOKEN_NAME = 'mcp-cypress-skills-token';
    const TEST_SKILL = 'cypress-test-skill';

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
        cy.apollo({mutation: createToken, variables: {tokenName: TOKEN_NAME, scopes: ['graphql', 'mcp']}}).then(result => {
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
        cy.apollo({mutation: deleteSkill, variables: {name: TEST_SKILL}});
        cy.apollo({mutation: deleteToken, variables: {tokenKey}});
    });

    afterEach(() => {
        cy.apollo({mutation: deleteSkill, variables: {name: TEST_SKILL}});
    });

    const callTool = (name: string, args: Record<string, unknown>) => {
        return cy.request({
            method: 'POST',
            url: '/modules/community-mcp',
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `APIToken ${apiToken}`
            },
            body: {
                jsonrpc: '2.0',
                id: 1,
                method: 'tools/call',
                params: {name, arguments: args}
            }
        });
    };

    // --- GraphQL API ---

    it('mcpSkills returns the default hello-jahia skill seeded on activation', () => {
        cy.apollo({query: getSkills}).then(result => {
            const skills: {name: string}[] = result.data.mcpSkills;
            const names = skills.map((s: {name: string}) => s.name);
            expect(names).to.include('hello-jahia');
        });
    });

    it('mcpSaveSkill creates a new skill and returns true', () => {
        cy.apollo({
            mutation: saveSkill,
            variables: {name: TEST_SKILL, mcpName: 'Cypress Test', description: 'Test skill', content: 'Test content'}
        }).its('data.mcpSaveSkill').should('eq', true);
    });

    it('mcpSkills reads back a saved skill with all fields', () => {
        cy.apollo({
            mutation: saveSkill,
            variables: {name: TEST_SKILL, mcpName: 'Cypress Test', description: 'A test skill', content: 'Hello from Cypress'}
        });
        cy.apollo({query: getSkills}).then(result => {
            const skill = result.data.mcpSkills.find((s: {name: string}) => s.name === TEST_SKILL);
            expect(skill).to.exist;
            expect(skill.mcpName).to.eq('Cypress Test');
            expect(skill.description).to.eq('A test skill');
            expect(skill.content).to.eq('Hello from Cypress');
        });
    });

    it('mcpSaveSkill updates an existing skill', () => {
        cy.apollo({mutation: saveSkill, variables: {name: TEST_SKILL, mcpName: 'v1', description: '', content: 'v1 content'}});
        cy.apollo({mutation: saveSkill, variables: {name: TEST_SKILL, mcpName: 'v2', description: '', content: 'v2 content'}});
        cy.apollo({query: getSkills}).then(result => {
            const skill = result.data.mcpSkills.find((s: {name: string}) => s.name === TEST_SKILL);
            expect(skill.mcpName).to.eq('v2');
            expect(skill.content).to.eq('v2 content');
        });
    });

    it('mcpDeleteSkill removes a skill and returns true', () => {
        cy.apollo({mutation: saveSkill, variables: {name: TEST_SKILL, mcpName: '', description: '', content: 'to delete'}});
        cy.apollo({mutation: deleteSkill, variables: {name: TEST_SKILL}})
            .its('data.mcpDeleteSkill').should('eq', true);
        cy.apollo({query: getSkills}).then(result => {
            const names = result.data.mcpSkills.map((s: {name: string}) => s.name);
            expect(names).not.to.include(TEST_SKILL);
        });
    });

    it('mcpDeleteSkill returns false for a non-existent skill', () => {
        cy.apollo({mutation: deleteSkill, variables: {name: 'does-not-exist'}})
            .its('data.mcpDeleteSkill').should('eq', false);
    });

    // --- MCP tools ---

    it('listSkills includes the default hello-jahia skill', () => {
        callTool('listSkills', {}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            const skills = JSON.parse(response.body.result.content[0].text);
            const names = skills.map((s: {name: string}) => s.name);
            expect(names).to.include('hello-jahia');
        });
    });

    it('getSkill returns content for the default hello-jahia skill', () => {
        callTool('getSkill', {name: 'default/hello-jahia'}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            expect(response.body.result.content[0].text).to.include('Hello Jahian');
        });
    });

    it('getSkill returns an error for a non-existent skill', () => {
        callTool('getSkill', {name: 'does-not-exist'}).then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(response.body.result.content[0].text).to.include('not found');
        });
    });

    it('listSkills reflects a newly created skill', () => {
        cy.apollo({mutation: saveSkill, variables: {name: TEST_SKILL, mcpName: 'MCP Listed', description: 'Listed via MCP', content: 'content'}});
        callTool('listSkills', {}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            const skills = JSON.parse(response.body.result.content[0].text);
            const found = skills.find((s: {name: string}) => s.name === TEST_SKILL);
            expect(found).to.exist;
            expect(found.description).to.eq('Listed via MCP');
        });
    });
});
