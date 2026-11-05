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
