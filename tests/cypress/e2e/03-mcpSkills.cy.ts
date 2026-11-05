import {DocumentNode} from 'graphql';
import {TOOL, callTool, createApiToken, deleteApiToken, deleteSkill, getSkills, saveSkill, toolPayload, toolText} from '../support/mcp';

describe('MCP Server — Skills', () => {
    const TOKEN_NAME = 'mcp-cypress-skills-token';
    const TEST_SKILL = 'cypress-test-skill';
    const NESTED_FOLDER = 'cypress-test-folder';
    const NESTED_SKILL = `${NESTED_FOLDER}/nested-skill`;
    // A skill this spec owns, so nothing depends on what the module seeded or on other specs.
    const OWN_SKILL = 'cypress-owned-skill';
    const OWN_CONTENT = 'Content owned by the skills spec';

    // eslint-disable-next-line @typescript-eslint/no-var-requires
    const getSkillStorage: DocumentNode = require('graphql-tag/loader!../fixtures/graphql/query/getSkillStorage.graphql');

    let apiToken: string;

    const skillNames = (skills: {name: string}[]) => skills.map(s => s.name);

    const cleanup = () => {
        deleteSkill(NESTED_SKILL);
        deleteSkill(NESTED_FOLDER);
        deleteSkill(TEST_SKILL);
        deleteSkill(OWN_SKILL);
    };

    before(() => {
        cy.login();
        createApiToken(TOKEN_NAME).then(token => {
            apiToken = token;
        });
        cleanup();
    });

    after(() => {
        cleanup();
        deleteApiToken(TOKEN_NAME);
    });

    beforeEach(() => {
        cleanup();
        saveSkill(OWN_SKILL, OWN_CONTENT, 'Owned', 'Owned by the skills spec');
    });

    // --- GraphQL API ---

    it('skills returns the default skill seeded on activation, keyed by its relative path', () => {
        getSkills().then(result => {
            expect(skillNames(result.data.communityMcp.skills)).to.include('default/hello-jahia');
        });
    });

    it('saveSkill creates a new skill and returns true', () => {
        saveSkill(TEST_SKILL, 'Test content', 'Cypress Test', 'Test skill')
            .its('data.communityMcp.saveSkill').should('eq', true);
    });

    it('skills reads back a saved skill with all fields', () => {
        saveSkill(TEST_SKILL, 'Hello from Cypress', 'Cypress Test', 'A test skill');
        getSkills().then(result => {
            const skill = result.data.communityMcp.skills.find((s: {name: string}) => s.name === TEST_SKILL);
            expect(skill).to.exist;
            expect(skill.mcpName).to.eq('Cypress Test');
            expect(skill.description).to.eq('A test skill');
            expect(skill.content).to.eq('Hello from Cypress');
        });
    });

    it('saveSkill updates an existing skill', () => {
        saveSkill(TEST_SKILL, 'v1 content', 'v1');
        saveSkill(TEST_SKILL, 'v2 content', 'v2');
        getSkills().then(result => {
            const skill = result.data.communityMcp.skills.find((s: {name: string}) => s.name === TEST_SKILL);
            expect(skill.mcpName).to.eq('v2');
            expect(skill.content).to.eq('v2 content');
        });
    });

    it('deleteSkill removes a skill and returns true', () => {
        deleteSkill(OWN_SKILL).its('data.communityMcp.deleteSkill').should('eq', true);
        getSkills().then(result => {
            expect(skillNames(result.data.communityMcp.skills)).not.to.include(OWN_SKILL);
        });
    });

    it('deleteSkill returns false for a non-existent skill', () => {
        deleteSkill('does-not-exist').its('data.communityMcp.deleteSkill').should('eq', false);
    });

    it('stores skills in jnt:contentList folders, as 1.x did, with a type hidden from content pickers', () => {
        cy.apollo({query: getSkillStorage}).then(result => {
            const jcr = result.data.jcr;
            expect(jcr.container.primaryNodeType.name).to.eq('jnt:contentList');
            expect(jcr.folder.primaryNodeType.name).to.eq('jnt:contentList');
            expect(jcr.skill.primaryNodeType.name).to.eq('mcp:skill');
            expect(jcr.skill.hidden, 'mcp:skill is a jmix:hiddenType').to.eq(true);
            expect(jcr.skill.droppable, 'mcp:skill is droppable into jnt:contentList').to.eq(true);
        });
    });

    // --- Nested skills ---

    it('saveSkill with a nested name creates the folders and lists the skill by its relative path', () => {
        saveSkill(NESTED_SKILL, 'nested content', 'Nested', 'A nested skill')
            .its('data.communityMcp.saveSkill').should('eq', true);
        getSkills().then(result => {
            const skill = result.data.communityMcp.skills.find((s: {name: string}) => s.name === NESTED_SKILL);
            expect(skill, 'nested skill listed by relative path').to.exist;
            expect(skill.content).to.eq('nested content');
        });
        callTool(apiToken, TOOL.getSkill, {name: NESTED_SKILL}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            expect(toolText(response)).to.include('nested content');
        });
        deleteSkill(NESTED_SKILL).its('data.communityMcp.deleteSkill').should('eq', true);
        getSkills().then(result => {
            expect(skillNames(result.data.communityMcp.skills)).not.to.include(NESTED_SKILL);
        });
    });

    // --- Traversal-style names are refused end to end ---

    ['../escape', 'a/../b', '/leading', 'trailing/', 'a//b', './x', '..', 'a b', 'a\\b'].forEach(name => {
        it(`refuses the unsafe skill name '${name}' through saveSkill, deleteSkill and community.skill.get`, () => {
            saveSkill(name, 'must not be stored').its('data.communityMcp.saveSkill').should('eq', false);
            deleteSkill(name).its('data.communityMcp.deleteSkill').should('eq', false);
            callTool(apiToken, TOOL.getSkill, {name}).then(response => {
                expect(response.body.result.isError).to.eq(true);
                expect(toolText(response)).not.to.include(OWN_CONTENT);
            });
            getSkills().then(result => {
                expect(skillNames(result.data.communityMcp.skills)).to.include(OWN_SKILL);
            });
        });
    });

    // --- MCP tools ---

    it('community.skill.list lists the skills by the path community.skill.get expects', () => {
        callTool(apiToken, TOOL.listSkills, {}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            const names = skillNames(toolPayload(response).skills);
            expect(names).to.include('default/hello-jahia');
            expect(names).to.include(OWN_SKILL);
        });
    });

    it('a name returned by community.skill.list can be passed straight to community.skill.get', () => {
        callTool(apiToken, TOOL.listSkills, {}).then(response => {
            const hello = toolPayload(response).skills.find((s: {name: string}) => s.name.endsWith('hello-jahia'));
            callTool(apiToken, TOOL.getSkill, {name: hello.name}).then(getResponse => {
                expect(getResponse.body.result.isError).to.eq(false);
                expect(toolPayload(getResponse).content).to.include('Hello Jahian');
            });
        });
    });

    it('community.skill.get returns the content of a skill this spec created', () => {
        callTool(apiToken, TOOL.getSkill, {name: OWN_SKILL}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            expect(toolPayload(response).content).to.eq(OWN_CONTENT);
        });
    });

    it('community.skill.get reports a non-existent skill as an error', () => {
        callTool(apiToken, TOOL.getSkill, {name: 'does-not-exist'}).then(response => {
            expect(response.body.result.isError).to.eq(true);
            expect(toolText(response)).to.include('not found');
        });
    });

    it('community.skill.list reflects a newly created skill', () => {
        saveSkill(TEST_SKILL, 'content', 'MCP Listed', 'Listed via MCP');
        callTool(apiToken, TOOL.listSkills, {}).then(response => {
            expect(response.body.result.isError).to.eq(false);
            const found = toolPayload(response).skills.find((s: {name: string}) => s.name === TEST_SKILL);
            expect(found).to.exist;
            expect(found.description).to.eq('Listed via MCP');
        });
    });
});
