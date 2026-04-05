# Jahia MCP Servlet

An OSGi bundle that exposes Jahia's GraphQL API as an [MCP (Model Context Protocol)](https://modelcontextprotocol.io/) server, enabling AI assistants such as Claude to query and mutate Jahia content directly.

## How it works

The servlet registers itself at `/modules/mcp` and implements the stateless MCP protocol over HTTP. When an MCP client calls the `executeGraphQL` tool, the servlet forwards the GraphQL request - along with the caller's `Authorization` header - to Jahia's internal GraphQL endpoint (`/modules/graphql`).

```
MCP Client (Claude Code)
    │  POST /modules/mcp
    │  Authorization: McpToken <token>
    ▼
McpServlet  ──── forwards ────►  /modules/graphql
                                  Authorization: APIToken <token>
```

## Requirements

- Jahia 8.2+ with `graphql-dxm-provider` deployed
- Java 17
- A Jahia personal API token with sufficient JCR permissions

## Installation

Deploy the bundle jar via the Jahia Module Manager.

## Configuration

The bundle is configured via the OSGi PID `org.jahia.community.mcp`:

| Property | Default | Description |
|---|---|---|
| `graphql.endpoint` | `http://localhost:8080/modules/graphql` | Internal GraphQL endpoint the servlet proxies to |

To override, create or edit `$JAHIA_HOME/digital-factory-data/karaf/etc/org.jahia.community.mcp.cfg`:

```properties
graphql.endpoint=http://localhost:8080/modules/graphql
```

## MCP client setup (Claude Code)

Add the following to `~/.claude/settings.json`:

```json
{
  "mcpServers": {
    "jahia-mcp": {
      "type": "http",
      "url": "http://localhost:8080/modules/mcp",
      "headers": {
        "authorization": "McpToken <your-token>"
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

**Example - list root nodes:**

```graphql
query {
  jcr(workspace: EDIT) {
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

The servlet reads the `Authorization` header from the incoming MCP request and forwards it as-is to the GraphQL endpoint. If the header uses the `CmpToken ` prefix (legacy), it is automatically rewritten to `APIToken `.

No token is stored in the bundle itself - the caller is always responsible for supplying valid credentials.
