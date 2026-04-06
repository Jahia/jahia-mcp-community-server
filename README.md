# Jahia MCP Community Server

**GitHub:** https://github.com/Jahia/jahia-mcp-community-server

An OSGi bundle that exposes Jahia's GraphQL API as an [MCP (Model Context Protocol)](https://modelcontextprotocol.io/) server, enabling AI assistants such as Claude to query and mutate Jahia content directly.

## How it works

The servlet registers itself at `/modules/mcp` and implements the stateless MCP protocol over HTTP. When an MCP client calls the `executeGraphQL` tool, the servlet dispatches the GraphQL request **in-process** via an OSGi service reference to `OsgiGraphQLHttpServlet` — no additional HTTP hop is involved.

```
MCP Client (Claude Code)
    │  POST /modules/mcp
    │  Authorization: APIToken <token>
    ▼
McpServlet  ──── OSGi service call ────►  OsgiGraphQLHttpServlet
                                           (graphql-dxm-provider)
```

The caller's `Authorization` header and the current Jahia user are forwarded to the GraphQL servlet so that all JCR permission checks apply normally.

## Requirements

- Jahia 8.2+ with `graphql-dxm-provider` deployed
- Java 17

## Installation

Deploy the bundle jar via the Jahia Module Manager.

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

Generate a personal API token in Jahia under **Administration → Profile → Personal API Tokens**.

## Available tool

### `executeGraphQL`

Executes any GraphQL query or mutation against Jahia's `graphql-dxm-provider`.

**Input:**

| Field | Type | Required | Description |
|---|---|---|---|
| `query` | string | yes | GraphQL query or mutation |
| `variables` | object | no | Variables map for the operation |

**Example — list root nodes:**

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

Supported operations include all `jcr` queries (`nodeByPath`, `nodeById`, `nodesByPath`, `nodesById`, `nodesByQuery`, `nodesByCriteria`), JCR mutations, admin queries, and any other operation registered by `graphql-dxm-provider` or its extensions.

## Authentication

The servlet reads the `Authorization` header from the incoming MCP request and passes it through to the internal GraphQL servlet. It also forwards the current Jahia user from the JCR session, so all existing JCR access controls are enforced.

No credentials are stored in the bundle itself — the caller is always responsible for supplying a valid API token.

Access to the `/modules/mcp` endpoint requires the usage of a personal API token **AND** the `mcp` permission, which is granted to users with the `admin` role (configured in `META-INF/configurations/org.jahia.bundles.api.authorization-mcp.yml`).

## Health check

A `GET /modules/mcp` request returns a JSON status response if the caller has the `mcp` permission:

```json
{"status":"Jahia MCP server running","version":"1.0.0"}
```
