import 'cypress-wait-until';
/**
 * Helpers for calling the community tools through the mcp-server endpoint.
 * The community module no longer has its own endpoint: its tools are contributed to /modules/mcp.
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
/* eslint-enable @typescript-eslint/no-var-requires */

/**
 * Set the whitelist as the logged-in administrator (cy.login() must have run), then wait until the
 * server reports it back: the configuration is applied asynchronously, and a request fired before
 * that would still be gated by the previous whitelist.
 */
export const setWhitelist = (whitelist: string[]) => {
    cy.apollo({mutation: saveSettingsDoc, variables: {whitelist}})
        .its('data.communityMcp.saveSettings').should('eq', true);
    cy.waitUntil(() => cy.apollo({query: getSettingsDoc}).then(result => {
        const current: string[] = result.data.communityMcp.settings.whitelist;
        return current.length === whitelist.length && current.every((entry, i) => entry === whitelist[i]);
    }), {errorMsg: `The whitelist ${JSON.stringify(whitelist)} was not applied`, timeout: 10000, interval: 200});
};

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

/** Delete a skill as the logged-in administrator (false when it does not exist). */
export const deleteSkill = (name: string) => cy.apollo({mutation: deleteSkillDoc, variables: {name}});

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
