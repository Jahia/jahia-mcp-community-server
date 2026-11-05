import 'cypress-wait-until';
/**
 * Helpers for calling the community tools through the mcp-server endpoint.
 * The community module no longer has its own endpoint: its tools are contributed to /modules/mcp.
 *
 * The suite needs a Jahia instance of its own: the whitelist is one server-wide configuration, and
 * the token, skill and probe names are fixed. Two runs against the same instance interfere.
 */
export const MCP_URL = '/modules/mcp';

export const TOOL = {
    execute: 'community.graphql.execute',
    introspect: 'community.graphql.introspect',
    listSkills: 'community.skill.list',
    getSkill: 'community.skill.get'
} as const;

/** JSON-RPC envelope returned by /modules/mcp. */
export interface McpBody {
    result?: {
        isError?: boolean;
        content?: {type: string; text: string}[];
        tools?: {name: string; annotations?: {readOnlyHint?: boolean; destructiveHint?: boolean}}[];
    };
    error?: {code: number; message: string};
}

export type McpResponse = Cypress.Response<McpBody>;

const rpc = (method: string, params: Record<string, unknown>) => ({jsonrpc: '2.0', id: 1, method, params});

/** POST a tools/call as the API-token holder. Never fails on HTTP status — assert on it instead. */
export const callTool = (apiToken: string, name: string, args: Record<string, unknown>): Cypress.Chainable<McpResponse> =>
    cy.request({
        method: 'POST',
        url: MCP_URL,
        headers: {'Content-Type': 'application/json', Authorization: `APIToken ${apiToken}`},
        body: rpc('tools/call', {name, arguments: args}),
        failOnStatusCode: false
    });

/** POST a tools/call with HTTP Basic credentials (used for non-admin callers). */
export const callToolAs = (username: string, password: string, name: string, args: Record<string, unknown>): Cypress.Chainable<McpResponse> =>
    cy.request({
        method: 'POST',
        url: MCP_URL,
        auth: {username, password},
        headers: {'Content-Type': 'application/json'},
        body: rpc('tools/call', {name, arguments: args}),
        failOnStatusCode: false
    });

/**
 * POST a tools/call with no Authorization header: the request is authenticated only by the browser
 * session cookie that cy.login() set (cy.request sends the browser cookies).
 */
export const callToolWithSessionCookie = (name: string, args: Record<string, unknown>): Cypress.Chainable<McpResponse> =>
    cy.request({
        method: 'POST',
        url: MCP_URL,
        headers: {'Content-Type': 'application/json'},
        body: rpc('tools/call', {name, arguments: args}),
        failOnStatusCode: false
    });

/** POST a tools/list as the API-token holder. */
export const listTools = (apiToken: string): Cypress.Chainable<McpResponse> =>
    cy.request({
        method: 'POST',
        url: MCP_URL,
        headers: {'Content-Type': 'application/json', Authorization: `APIToken ${apiToken}`},
        body: rpc('tools/list', {}),
        failOnStatusCode: false
    });

/** The single text content block of a tools/call result. */
export const toolText = (response: McpResponse): string => response.body.result.content[0].text;

/** The JSON payload of a tools/call result (success payload, or {error:{code,message,hint}}). */
// eslint-disable-next-line @typescript-eslint/no-explicit-any -- tool payloads are free-form JSON
export const toolPayload = (response: McpResponse): any => JSON.parse(toolText(response));

// ---------------------------------------------------------------------------------------------
// Shared fixtures. Every spec provisions its own token, whitelist and skills and restores them, so
// specs never depend on each other or on execution order.
// ---------------------------------------------------------------------------------------------
/* eslint-disable @typescript-eslint/no-var-requires */
const saveSettingsDoc = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSettings.graphql');
const createTokenDoc = require('graphql-tag/loader!../fixtures/graphql/mutation/createToken.graphql');
const deleteTokenDoc = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteToken.graphql');
const listTokensDoc = require('graphql-tag/loader!../fixtures/graphql/query/listTokens.graphql');
const saveSkillDoc = require('graphql-tag/loader!../fixtures/graphql/mutation/saveSkill.graphql');
const deleteSkillDoc = require('graphql-tag/loader!../fixtures/graphql/mutation/deleteSkill.graphql');
const getSettingsDoc = require('graphql-tag/loader!../fixtures/graphql/query/getSettings.graphql');
const getSkillsDoc = require('graphql-tag/loader!../fixtures/graphql/query/getSkills.graphql');
const getStoredWhitelistDoc = require('graphql-tag/loader!../fixtures/graphql/query/getStoredWhitelist.graphql');
const getClusteredDoc = require('graphql-tag/loader!../fixtures/graphql/query/getClustered.graphql');
/* eslint-enable @typescript-eslint/no-var-requires */

const sameEntries = (current: string[], expected: string[]) =>
    current.length === expected.length && current.every((entry, i) => entry === expected[i]);

/** The whitelist the module serves (its applied snapshot), as the logged-in administrator. */
export const getWhitelist = (): Cypress.Chainable<string[]> =>
    cy.apollo({query: getSettingsDoc}).then(result => result.data.communityMcp.settings.whitelist as string[]);

/** Whether the module reports that Jahia runs as a cluster (`communityMcp { settings { clustered } }`). */
export const getClustered = (): Cypress.Chainable<boolean> =>
    cy.apollo({query: getClusteredDoc}).then(result => result.data.communityMcp.settings.clustered as boolean);

/**
 * The `whitelist` value stored in ConfigurationAdmin, read through Jahia's own configuration query.
 * ConfigurationAdmin stores a write before the writing call returns, whereas the module applies it
 * later (DS @Modified is asynchronous): this read is the one that proves whether a write happened.
 */
export const getStoredWhitelist = (): Cypress.Chainable<string> =>
    cy.apollo({query: getStoredWhitelistDoc})
        .then(result => (result.data.admin.jahia.configuration?.value ?? '') as string);

/** Wait until the module serves `whitelist` (the configuration is applied asynchronously). */
export const waitForWhitelist = (whitelist: string[]) => {
    cy.waitUntil(() => getWhitelist().then(current => sameEntries(current, whitelist)),
        {errorMsg: `The whitelist ${JSON.stringify(whitelist)} was not applied`, timeout: 10000, interval: 200});
};

/**
 * Set the whitelist as the logged-in administrator (cy.login() must have run), then wait until the
 * server reports it back: the configuration is applied asynchronously, and a request fired before
 * that would still be gated by the previous whitelist. The stored value is checked too, which keeps
 * {@link expectWhitelistUnchanged} honest: its stored read is shown here to see every write.
 */
export const setWhitelist = (whitelist: string[]) => {
    cy.apollo({mutation: saveSettingsDoc, variables: {whitelist}})
        .its('data.communityMcp.saveSettings').should('eq', true);
    waitForWhitelist(whitelist);
    getStoredWhitelist().should('eq', whitelist.join(','));
};

/**
 * Assert that a call which must not change the whitelist did not: the stored configuration still
 * holds `expected` (a write is stored before the call returns, so it cannot hide behind the
 * asynchronous apply), and the module still serves it. `expected` must have been set with
 * {@link setWhitelist} first, so the served value is not a stale one.
 */
export const expectWhitelistUnchanged = (expected: string[]) => {
    getStoredWhitelist().should('eq', expected.join(','));
    getWhitelist().should('deep.equal', expected);
};

/** The names of the personal API tokens that the logged-in administrator can see. */
export const getApiTokenNames = (): Cypress.Chainable<string[]> =>
    cy.apollo({query: listTokensDoc, fetchPolicy: 'no-cache'})
        .then(result => result.data.admin.personalApiTokens.tokens.nodes.map((t: {name: string}) => t.name) as string[]);

/** Create an API token under `name` (replacing a stale one of the same name) and return its value. */
export const createApiToken = (name: string): Cypress.Chainable<string> => {
    cy.apollo({query: listTokensDoc}).then(result => {
        const nodes: {key: string; name: string}[] = result.data.admin.personalApiTokens.tokens.nodes;
        nodes.filter(t => t.name === name).forEach(t => {
            cy.apollo({mutation: deleteTokenDoc, variables: {tokenKey: t.key}});
        });
    });
    return cy.apollo({mutation: createTokenDoc, variables: {tokenName: name}})
        .then(result => result.data.admin.personalApiTokens.createToken as string);
};

/** Delete the API token named `name`, if it exists. */
export const deleteApiToken = (name: string) => {
    cy.apollo({query: listTokensDoc}).then(result => {
        const nodes: {key: string; name: string}[] = result.data.admin.personalApiTokens.tokens.nodes;
        nodes.filter(t => t.name === name).forEach(t => {
            cy.apollo({mutation: deleteTokenDoc, variables: {tokenKey: t.key}});
        });
    });
};

/** Save (create or update) a skill as the logged-in administrator. */
export const saveSkill = (name: string, content: string, mcpName = '', description = '') =>
    cy.apollo({mutation: saveSkillDoc, variables: {name, mcpName, description, content}});

/** Delete a skill as the logged-in administrator; a refusal (for example no such skill) fails the test. */
export const deleteSkill = (name: string) => cy.apollo({mutation: deleteSkillDoc, variables: {name}});

/** Delete a skill if it exists, for cleanup: a refusal (no such skill) is ignored. */
export const removeSkill = (name: string) =>
    cy.apollo({mutation: deleteSkillDoc, variables: {name}, errorPolicy: 'all'});

/** Assert that a skill mutation was refused with a GraphQL error whose message includes `expected`. */
const expectRefused = (field: 'saveSkill' | 'deleteSkill', expected: string) =>
    (result: {data?: {communityMcp?: Record<string, unknown> | null} | null; errors?: readonly {message: string}[]}) => {
        expect(result.errors, `GraphQL errors of ${field}`).to.have.length.greaterThan(0);
        expect(result.errors[0].message).to.include(expected);
        expect(result.data?.communityMcp?.[field] ?? null, `${field} returns no value`).to.eq(null);
    };

/** Call saveSkill and assert it is refused with a GraphQL error whose message includes `expected`. */
export const saveSkillRefused = (name: string, content: string, expected: string) =>
    cy.apollo({mutation: saveSkillDoc, variables: {name, mcpName: '', description: '', content}, errorPolicy: 'all'})
        .then(expectRefused('saveSkill', expected));

/** Call deleteSkill and assert it is refused with a GraphQL error whose message includes `expected`. */
export const deleteSkillRefused = (name: string, expected: string) =>
    cy.apollo({mutation: deleteSkillDoc, variables: {name}, errorPolicy: 'all'})
        .then(expectRefused('deleteSkill', expected));

/** The skills as the logged-in administrator sees them. */
export const getSkills = () => cy.apollo({query: getSkillsDoc});

/** POST a GraphQL document to /modules/graphql with HTTP Basic credentials; never fails on status. */
export const graphqlAs = (username: string, password: string, query: string, variables: Record<string, unknown> = {}) =>
    cy.request({
        method: 'POST',
        url: '/modules/graphql',
        auth: {username, password},
        headers: {'Content-Type': 'application/json'},
        body: {query, variables},
        failOnStatusCode: false
    });
