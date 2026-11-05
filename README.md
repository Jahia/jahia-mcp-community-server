# Jahia MCP Community Server

**GitHub:** https://github.com/Jahia/jahia-mcp-community-server

An extension of Jahia's [`mcp-server`](https://github.com/Jahia/mcp-server) module. It contributes
**generic GraphQL tools** (gated by an operation whitelist) and **JCR-stored skills** to the
[Model Context Protocol (MCP)](https://modelcontextprotocol.io/) endpoint that `mcp-server` exposes,
so AI assistants such as Claude can reach any Jahia GraphQL operation — not only the curated
`site.*`, `page.*` and `content.*` tools — when an administrator allows it.

> **2.0 is a breaking change.** Versions 1.x ran their own MCP endpoint at
> `/modules/community-mcp`. From 2.0 that endpoint is gone: the tools are served by `mcp-server` at
> `/modules/mcp`, under new names. See [Migrating from 1.x](#migrating-from-1x).

## How it works

```
MCP client (Claude Code, Claude Desktop, Cursor…)
    │  POST /modules/mcp            (mcp-server: authentication, JSON-RPC, tool catalog)
    │  Authorization: APIToken <token>
    ▼
mcp-server ToolRegistry ──► community.graphql.execute ──[admin gate]──[whitelist]──► OsgiGraphQLHttpServlet
                        ├─► community.graphql.introspect                              (in-process, as the caller)
                        ├─► community.skill.list / community.skill.get ──► JCR skills
                        └─► site.* page.* content.* … (built-in mcp-server tools)
```

Each tool is an OSGi Declarative Services component implementing `mcp-server`'s public
`org.jahia.modules.mcp.spi.tool.JahiaMcpTool` SPI; `mcp-server` discovers it automatically. GraphQL
documents are dispatched **in-process** to `graphql-dxm-provider` under the calling user's JCR
identity, so JCR ACLs apply normally.

## Requirements

- Jahia 8.2+ with `graphql-dxm-provider`
- `mcp-server` **0.5.x** (the SPI is pre-1.0; this module imports `org.jahia.modules.mcp.spi.tool` in the range `[0.5,0.6)`)
- `personal-api-tokens` (for API token authentication)
- Java 17

## Installation

Deploy `mcp-server` first, then this bundle (Module Manager, or drop the JAR into `$JAHIA_HOME/modules/`).

On first activation the module seeds a set of default skills into JCR under `/sites/systemsite/contents/mcp-skills/`.

## Authentication and access

Authentication is handled by `mcp-server`: send a personal API token (or HTTP Basic credentials)
to `/modules/mcp`.

```
Authorization: APIToken <your-token>
```

`mcp-server` admits any authenticated, non-guest user. The **community tools add their own gate**:
the caller must hold the `admin` permission on the repository root (a server administrator). Other
users still see the community tools in `tools/list`, but every call returns a structured
`forbidden` error. The built-in `mcp-server` tools are unaffected.

> Why not an API scope? Tools run on MCP worker threads where Jahia's security-filter scope context
> is not bound, and `PermissionService.hasPermission(...)` returns `true` in that state. The gate is
> therefore a JCR permission check on the caller's own session, which is reliable on any thread.

## MCP client setup (Claude Code)

```json
{
  "mcpServers": {
    "jahia": {
      "type": "http",
      "url": "http://localhost:8080/modules/mcp",
      "headers": {
        "authorization": "APIToken <your-token>"
      }
    }
  }
}
```

## Tools

| Tool | Safety | Purpose |
|---|---|---|
| `community.graphql.execute` | destructive | Run any GraphQL query or mutation, subject to the whitelist |
| `community.graphql.introspect` | read-only | Discover the GraphQL schema: type list + root types, or specific types (bounded) |
| `community.skill.list` | read-only | List skills: `name`, `mcpName`, `description` |
| `community.skill.get` | read-only | Read one skill's full Markdown content |

Results follow `mcp-server`'s contract: a JSON text block on success, and
`{"error":{"code","message","hint"}}` with `isError: true` on failure (`forbidden`, `validation`,
`not_found`, `internal`).

### `community.graphql.execute`

| Field | Type | Required | Description |
|---|---|---|---|
| `query` | string | yes | GraphQL query or mutation |
| `variables` | object | no | Variables map for the operation |

Returns the GraphQL response JSON (`data` / `errors`). GraphQL-level errors arrive inside the
payload; an HTTP-level failure of the GraphQL servlet is reported as an error result. It is
announced as **destructive** because it can run any mutation, so well-behaved clients ask for
confirmation before calling it.

### `community.graphql.introspect`

| Field | Type | Required | Description |
|---|---|---|---|
| `typeNames` | string[] | no | 1–25 GraphQL type names to describe in full |

- **No arguments:** returns `schema` (root type names and every type's `name` + `kind`) and the
  full details of the root `Query` and `Mutation` types.
- **`typeNames`:** returns the full details (fields, arguments, input fields, enum values) of those
  types only.

Every answer also carries `skippedTypes` (unknown, or the request failed) and `truncatedTypes`
(not fetched because the result reached its 100,000-character budget — ask for them in a further
call). Each type is fetched in its own request, so Jahia's *bad-faith introspection guard*
(which rejects documents with more than one `__Type.fields` selection) never trips; type names are
passed as GraphQL variables, never formatted into the document.

### `community.skill.list` / `community.skill.get`

`community.skill.list` returns `{"skills":[{"name","mcpName","description"}]}`. Each `name` is the
path relative to the skills root (e.g. `default/hello-jahia`) and can be passed as-is to
`community.skill.get`, which returns `{"name","mcpName","description","content"}`.

## Skills

Skills are Markdown documents stored as `mcp:skill` JCR nodes. They deliver Jahia-specific
knowledge and instructions to any MCP client session.

### Default skills

Default skills are defined as `.md` files under `src/main/resources/skills/` and seeded into JCR on
module activation, only when the node does not already exist.

### Managing skills and settings via GraphQL

The module's GraphQL operations are grouped under a single `communityMcp` namespace on `Query` and
`Mutation` (all require the `admin` permission).

```graphql
# List all skills
query {
    communityMcp {
        skills { name mcpName description content }
    }
}

# Create or update a skill
mutation {
    communityMcp {
        saveSkill(
            name: "my-skill",
            mcpName: "My Skill Display Name",
            description: "What this skill does",
            content: "# My Skill\n\nInstructions for Claude..."
        )
    }
}

# Delete a skill
mutation {
    communityMcp {
        deleteSkill(name: "my-skill")
    }
}

# Read / write the whitelist
query { communityMcp { settings { whitelist } } }
mutation { communityMcp { saveSettings(whitelist: ["currentUser", "jcr"]) } }
```

### Adding a bundled default skill

Drop a `.md` file with frontmatter into `src/main/resources/skills/<subfolder>/`:

```markdown
---
mcpName: My Skill Display Name
description: Short description of what this skill does
---
Full Markdown instructions here...
```

The file name (without `.md`) becomes the JCR node name. The folder hierarchy mirrors the JCR
sub-folder structure under `mcp-skills/`.

## Access control — Allow List

The admin UI at **Administration → MCP Server** restricts which GraphQL operations
`community.graphql.execute` may run. Queries and mutations are listed — and allowed — separately.
The other community tools are not affected by the whitelist.

| Whitelist state | Effect |
|---|---|
| Empty (default) | All operations are allowed |
| Non-empty | Only listed operations (and their sub-paths) are allowed |

Each entry names an **operation type** and a dot-separated field path that mirrors the GraphQL
selection hierarchy:

| Entry | Covers |
|---|---|
| `query:admin` | Every query under `admin` |
| `query:admin.jahia.isAlive` | Only that query field |
| `mutation:jcr` | Every mutation under `jcr` |
| `mutation:jcr.deleteNode` | Only that mutation |

Allowing a query never allows the mutation of the same name: `query:jcr` does **not** permit
`mutation { jcr { … } }`. A document is allowed only if every operation it contains is.

**Legacy entries.** An entry without a prefix (`jcr`, as written by 1.x) is accepted and treated as
**query-only**; a `WARN` lists such entries when the configuration loads, and the admin UI shows
them as `query:` entries, ready to save in the new form. Malformed entries and other operation types
(e.g. `subscription:`) grant nothing; `saveSettings` rejects them.

Entries are persisted in the OSGi configuration `org.jahia.community.mcp`:

```properties
whitelist=query:jcr,query:currentUser,mutation:jcr.deleteNode
```

Every blocked operation is logged at `WARN` with the path, user and client IP:

```
WARN GraphQLWhitelistGate - MCP operation blocked: path='mutation:jcr.deleteNode', reason=not in whitelist, user='john', ip='10.0.0.5'
```

## Security notes

- **Allow-all by default.** With an empty whitelist every GraphQL operation is permitted to server
  administrators (bounded by JCR ACLs), and a `WARN` is logged on every call. Treat it as an
  out-of-box convenience; configure an explicit whitelist in production.
- **Named fragment spreads** (`...Name`) are refused while a whitelist is active — the gate cannot
  resolve them, so it fails closed. Inline fragments are supported.
- **Unparseable documents** are refused while a whitelist is active (SEC-364).
- **Client IP** in audit lines is the TCP peer (`getRemoteAddr()`); `X-Forwarded-For` is ignored.
  Configure Tomcat's `RemoteIpValve` behind a trusted proxy.
- **Skills are read and written with a system session**, bypassing per-node ACLs. This is benign
  while the tools are restricted to administrators.
- **Request size / transport** limits are now those of `mcp-server` (this module no longer owns an
  endpoint). Note that `mcp-server` 0.5 reads the request body without a size cap — the 2 MB cap of
  1.x is gone with the old servlet.
- **Audit.** Every call records an mcp-server audit event (`tool`, `user`, error `code`);
  `community.graphql.execute` adds `queryHash` (SHA-256 of the document — never the text or variables).

## Migrating from 1.x

| 1.x | 2.0 |
|---|---|
| Endpoint `/modules/community-mcp` | `/modules/mcp` (served by `mcp-server`) |
| `executeGraphQL` | `community.graphql.execute` |
| `introspectSchema` | `community.graphql.introspect` (bounded: type list + root types, or `typeNames`) |
| `listSkills` | `community.skill.list` (returns `{"skills":[…]}`, names are relative paths) |
| `getSkill` | `community.skill.get` (returns JSON with a `content` field) |
| Token scopes `graphql` + `community-mcp` | Any token accepted by `mcp-server`; caller must be a server administrator |
| `GET /modules/community-mcp` health check | Removed — use MCP `tools/list` |
| GraphQL namespace `mcp { … }` | `communityMcp { … }` |
| Errors as `{"errors":[{"message"}]}` text | `{"error":{"code","message","hint"}}` |

| Whitelist entry `jcr` (query **and** mutation) | `jcr` is query-only; use `query:jcr` / `mutation:jcr` |

Update your MCP client URL and any tool names hard-coded in prompts or skills, and review your
whitelist: a 1.x entry that was meant to allow mutations needs an explicit `mutation:` entry.

## Testing

```bash
mvn clean install              # Java unit tests + Jest + bundle
```

Docker-based Cypress integration tests live in `tests/` (they provision `mcp-server` 0.5.0-SNAPSHOT).

```bash
cd tests
cp .env.example .env          # fill JAHIA_IMAGE and JAHIA_LICENSE
yarn install
./ci.build.sh                  # build the Cypress Docker image
./ci.startup.sh                # start Jahia + run tests + collect results
```

| Spec | What is tested |
|---|---|
| `01-mcpSettings.cy.ts` | GraphQL settings API: read, write, round-trip, prefixed entries, rejection of malformed entries |
| `02-mcpEndpoint.cy.ts` | `community.graphql.execute`: whitelist enforcement, dot-path coverage, queries vs mutations, introspection pass-through, 401 when unauthenticated |
| `03-mcpSkills.cy.ts` | Skills GraphQL API, `community.skill.list` → `community.skill.get` round-trip, default skill seeding |
| `04-mcpToolCatalogAndGate.cy.ts` | Tools listed in mcp-server's catalog with safety annotations; non-administrators refused |
| `05-mcpToolGating.cy.ts` | Only `community.graphql.execute` is whitelist-gated; bounded introspection (default, `typeNames`, cap) |
| `06-mcpWhitelistEnforcement.cy.ts` | Fail-closed guards: named fragments, multi-operation documents, SEC-364 bypasses |
| `07-mcpCallerIdentity.cy.ts` | GraphQL runs as the calling user |
