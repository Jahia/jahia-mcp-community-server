# Jahia MCP Community Server

**GitHub:** https://github.com/Jahia/jahia-mcp-community-server

An OSGi bundle that exposes Jahia's GraphQL API as a [Model Context Protocol (MCP)](https://modelcontextprotocol.io/) server, enabling AI assistants such as Claude to query and mutate Jahia content directly over a secure HTTP endpoint.

## How it works

The servlet registers at `/modules/mcp` and implements the stateless MCP JSON-RPC 2.0 protocol over HTTP. When an MCP client calls a tool, the servlet dispatches the GraphQL request **in-process** via an OSGi service reference to `OsgiGraphQLHttpServlet` — no extra HTTP hop is involved.

```
MCP Client (Claude Code)
    │  POST /modules/mcp
    │  Authorization: APIToken <token>
    ▼
McpServlet  ──[whitelist check]──►  OsgiGraphQLHttpServlet
                                     (graphql-dxm-provider)
```

The caller's `Authorization` header and the current Jahia user are forwarded so that all JCR permission checks apply normally.

## Requirements

- Jahia 8.2+ with `graphql-dxm-provider` deployed
- `personal-api-tokens` module deployed (for API token authentication)
- Java 17

## Installation

Deploy the bundle JAR via the Jahia Module Manager or drop it into `$JAHIA_HOME/modules/`.

## Authentication

Access to `/modules/mcp` requires a personal API token with both the **`graphql`** and **`mcp`** scopes.

Generate one in Jahia under **Administration → Profile → Personal API Tokens**, or via GraphQL:

```graphql
mutation {
    admin {
        personalApiTokens {
            createToken(name: "my-mcp-token", scopes: ["graphql", "mcp"])
        }
    }
}
```

Pass the token in every request:

```
Authorization: APIToken <your-token>
```

The `mcp` permission is automatically granted to users with the `admin` role (configured in `META-INF/configurations/org.jahia.bundles.api.authorization-mcp.yml`).

## MCP client setup (Claude Code)

Add the following to `~/.claude/settings.json`:

```json
{
  "mcpServers": {
    "jahia-mcp": {
      "type": "http",
      "url": "http://localhost:8080/modules/mcp",
      "headers": {
        "authorization": "APIToken <your-token>"
      }
    }
  }
}
```

## Available tools

### `introspectSchema`

Returns all available top-level GraphQL query and mutation operations with full type details. Call this first to discover what operations and arguments are available before calling `executeGraphQL`.

No input arguments required.

### `executeGraphQL`

Executes any GraphQL query or mutation against Jahia's `graphql-dxm-provider`, subject to the configured whitelist.

| Field | Type | Required | Description |
|---|---|---|---|
| `query` | string | yes | GraphQL query or mutation |
| `variables` | object | no | Variables map for the operation |

**Example — list root child nodes:**

```graphql
query {
    jcr {
        nodeByPath(path: "/") {
            children {
                nodes {
                    name
                    path
                    primaryNodeType { name }
                }
            }
        }
    }
}
```

Introspection fields (`__schema`, `__type`) always pass regardless of whitelist configuration.

## Access control — Allow List

The admin UI at **Administration → MCP Server** lets you restrict which GraphQL operations the MCP server may execute.

### Behaviour

| Whitelist state | Effect |
|---|---|
| Empty | All operations are allowed |
| Non-empty | Only listed operations (and their sub-paths) are allowed |

### Dot-path entries

Entries are dot-separated field paths that mirror the GraphQL selection hierarchy:

| Entry | Covers |
|---|---|
| `admin` | All operations under `admin` |
| `admin.jahia` | All operations under `admin.jahia` |
| `admin.jahia.isAlive` | Only that specific nested field |

Whitelist entries are persisted in the OSGi configuration `org.jahia.community.mcp` and can also be set manually in `META-INF/configurations/org.jahia.community.mcp.cfg`:

```properties
whitelist=jcr,currentUser,admin.jahia.isAlive
```

### Blocked operation log

Every blocked operation is logged at `WARN` level with the operation path, the authenticated user, and the client IP:

```
WARN McpServlet - MCP operation blocked: path='admin.jahia.shutdown', reason=not in the whitelist, user='john', ip='10.0.0.5'
```

## Health check

A `GET /modules/mcp` request returns a JSON status response for authenticated users with the `mcp` permission:

```json
{"status":"Jahia MCP server running","version":"1.0.0","tools":["executeGraphQL","introspectSchema"]}
```

## Testing

Docker-based Cypress integration tests live in `tests/`. They require a running Docker environment.

```bash
cd tests
cp .env.example .env          # fill JAHIA_IMAGE and JAHIA_LICENSE
yarn install
./ci.build.sh                  # build the Cypress Docker image
./ci.startup.sh                # start Jahia + run tests + collect results
```

Test results are written to `tests/results/`.

### Test coverage

| Spec | What is tested |
|---|---|
| `01-mcpSettings.cy.ts` | GraphQL settings API: read, write, round-trip, dot-path persistence |
| `02-mcpEndpoint.cy.ts` | MCP endpoint: whitelist enforcement, dot-path coverage, introspection pass-through, 401 for unauthenticated requests |
