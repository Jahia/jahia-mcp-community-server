# CLAUDE.md — Jahia MCP Community Server

## What this module does

An **extension of Jahia's `mcp-server` module** (2.0+). It contributes four MCP tools to the
`mcp-server` endpoint (`/modules/mcp`) through its public tool SPI:
`community.graphql.execute`, `community.graphql.introspect`, `community.skill.list` and
`community.skill.get`. The module owns **no endpoint and no MCP SDK**: `mcp-server` handles
transport, authentication, JSON-RPC and the tool catalog. Access to the community tools is limited
to server administrators; `community.graphql.execute` is additionally gated by an optional dot-path
whitelist. Skills (Markdown instruction documents) are stored in JCR.

## Source layout

```
src/main/java/org/jahia/community/mcp/
    McpSkillService.java                   # OSGi component — JCR CRUD for mcp:skill nodes
    access/
        CommunityMcpAccess.java            # Administrator gate shared by every tool (JCR 'admin' on /)
        GraphQLWhitelistGate.java          # Operation-qualified dot-path whitelist gate (graphql-java AST)
    config/
        McpConfigService.java              # OSGi ManagedService — reads whitelist from .cfg (WARNs legacy entries)
        WhitelistEntries.java              # Entry syntax: query:<path> / mutation:<path>; legacy = query-only
    tools/                                 # JahiaMcpTool DS components (mcp-server SPI)
        GraphQLExecuteTool.java            # community.graphql.execute   (DESTRUCTIVE)
        GraphQLIntrospectTool.java         # community.graphql.introspect (READ_ONLY)
        SkillListTool.java                 # community.skill.list        (READ_ONLY)
        SkillGetTool.java                  # community.skill.get         (READ_ONLY)
        InternalGraphQLClient.java         # In-process POST to OsgiGraphQLHttpServlet
        ToolSupport.java                   # Schema loading, audit, header helpers
    graphql/
        McpGraphQLExtensionsProvider.java  # Registers GraphQL extensions
        McpQueryExtension.java             # Adds `communityMcp` to Query → McpQuery
        McpQuery.java                      # communityMcp { settings | skills }
        McpMutationExtension.java          # Adds `communityMcp` to Mutation → McpMutation
        McpMutation.java                   # communityMcp { saveSettings | saveSkill | deleteSkill }

src/main/resources/
    schemas/community.*.json               # Hand-written JSON Schemas (draft 2020-12), one per tool
    META-INF/definitions.cnd               # mcp:skill node type
    META-INF/configurations/org.jahia.community.mcp.cfg   # Default whitelist (empty)
    skills/default/*.md                    # Default skills seeded into JCR on activation

src/javascript/CommunityMcpConfig/         # React admin UI (whitelist tree)
tests/                                     # Cypress Docker integration tests (provision mcp-server)
```

## Architecture

### Tools (mcp-server SPI)

- Each tool is `@Component(service = JahiaMcpTool.class, immediate = true)`; mcp-server's
  `ToolRegistry` binds it dynamically. Names must match `^[a-z]++(?:\.[a-z][a-z0-9_]*+)++$` and are
  namespaced `community.*` so they never collide with built-in tools (duplicate names: last bound wins).
- `execute()` must return a `ToolResult` (never null) and **always** record an audit event through
  `ctx.audit()` — `ToolSupport.audited(...)` wraps every path.
- Recoverable failures → `ToolResult.error(code, message, hint)` with `ToolError.CODE_*`; never leak
  exception messages to the client (log them).
- Declare `safety()` explicitly: execute is `DESTRUCTIVE` (arbitrary mutations), the rest `READ_ONLY`.
- Input schemas are loaded from `schemas/<toolName>.json`; mcp-server's SDK validates against them.

### Threading — read before touching auth or GraphQL dispatch

mcp-server runs tools on **Reactor worker threads**. Around each call it binds the caller on the
JCR thread state (`JCRSessionFactory.setCurrentUser`, locale) and cleans up afterwards
(`McpAdapterServlet.cleanupJcrThreadState`, which mirrors `endRequest()`).

- **Never** call `JcrSessionFilter.endRequest()` or `setCurrentUser(...)` inside a tool — it would
  drop the caller identity mid-call.
- **Never** gate on `PermissionService.hasPermission(...)` inside a tool: no security-filter scope is
  bound on the worker thread, and `PermissionServiceImpl` returns `true` when scopes are null.
  `CommunityMcpAccess.requireAdministrator` uses a JCR check instead:
  `ctx.withUserSession(s -> ((JCRNodeWrapper) s.getRootNode()).hasPermission("admin"))`, failing closed.
- The same missing scope context means graphql-dxm-provider API-scope checks do not apply to the
  in-process GraphQL call (same as 1.x); JCR ACLs do.

### GraphQL dispatch (InternalGraphQLClient)

Builds a synthetic JSON POST (`HttpServletRequestWrapper` over a no-op proxy) and calls
`OsgiGraphQLHttpServlet.service()` in-process, forwarding the caller's `Authorization` header from
`ctx.request()`. The capturing response stores `setStatus`/`sendError` and writes both the output
stream and the writer into one UTF-8 byte buffer (1.x cast bytes to chars — garbling non-ASCII —
and never saw the status).

### introspect — bounded discovery

Jahia's bad-faith introspection guard rejects any document with more than one `__Type.fields`
selection, so each type is fetched in its own request, with its name as a GraphQL **variable**
(`query($name: String!) { __type(name: $name) … }` — never `String.format`, since `typeNames` is
client input). No arguments → `__schema` summary (types reduced to `{name, kind}`) + root
Query/Mutation details; `typeNames` (1–`MAX_TYPE_NAMES`=25, GraphQL-name pattern) → those types
only, no schema step. Output budget `MAX_RESULT_CHARS`=100,000: once reached, remaining names go to
`truncatedTypes` (the first type is always included); unknown/failed types go to `skippedTypes`.

### Skills mechanism

`McpSkillService` (`@Component(immediate=true)`) seeds `skills/**.md` into
`/sites/systemsite/contents/mcp-skills/` on activation (YAML frontmatter `mcpName`, `description`),
lists skills with JCR-SQL2 `ISDESCENDANTNODE`, and reads/writes with a **system session**
(intentional, benign while the tools are admin-only). `SkillEntry.path` is the path relative to the
skills root (e.g. `default/hello-jahia`) — `community.skill.list` returns it as `name` because it is
the key `getSkill` expects. Names are validated (`SKILL_NAME_PATTERN`, no `.`/`..` segments,
`assertDescendant`).

### Access control (GraphQLWhitelistGate)

- Entry syntax (`config/WhitelistEntries`): `query:<path>` / `mutation:<path>`, `<path>` =
  `name(.name)*`. **An unprefixed (legacy 1.x) entry is query-only** — mutations are always explicit.
  Malformed entries and other operation types normalize to nothing (grant nothing);
  `McpMutation.saveSettings` rejects them; `McpConfigService` WARNs legacy and malformed entries.
- Internal form puts the operation type first: `query.jcr.nodeByPath`, `mutation.jcr.deleteNode`.
  `extractOperationPaths` qualifies every extracted path the same way, for **every** operation of
  the document — a mutation cannot hide behind an allowed query.
- Allow-all is decided on the **raw** list: empty → allow-all (WARN on every call, intentional
  default). A non-empty list of only malformed entries denies everything.
- Non-empty: parse once with `graphql.parser.Parser`; unparseable → deny (SEC-364); named fragment
  spread → deny (SEC-2); `__*` segments removed (introspection passes).
- A path passes if `pathCoveredBy(P, E)` (`P == E || P.startsWith(E + ".")`) or
  `pathIsContainerOf(P, E)` (`E.startsWith(P + ".")`) for some normalized entry. Denial messages and
  logs show the user-facing form (`mutation:jcr.deleteNode`).

### React admin UI

`Administration → MCP Server`; lazy-loaded operation tree with Queries and Mutations as separate
groups (paths `query:<name>…` / `mutation:<name>…`, see `operationTree.js`); legacy entries are
shown as `query:` entries and the form starts dirty so they can be saved in the new form; saves
through `communityMcp { saveSettings(whitelist) }`. Query and Mutation field lists are fetched in two
documents (one `__Type.fields` each) because of the bad-faith guard.

## Key implementation constraints

- **mcp-server SPI is pre-1.0.** `Import-Package: org.jahia.modules.mcp.spi.tool;version="[0.5,0.6)"`.
  Bump the range deliberately when mcp-server ships a new minor. Jackson types in the SPI come from
  the platform (both bundles import `com.fasterxml.jackson.databind`).
- `org.slf4j` is imported as `[1.7,3)` to resolve against Pax Logging on any Jahia 8.2.
- Skill display name is `jcr:title` (`mix:title`), exposed as `mcpName`.
- Default `.cfg` files must start with `# default configuration - won't be overridden`.
- GraphQL extensions live under ONE namespace (`communityMcp`) — never add flat root fields.

## Build

```bash
mvn clean install          # Java unit tests (JUnit 5) + Jest + React build + bundle
```

Requires `org.jahia.modules:mcp-server:0.5.0-SNAPSHOT` in a reachable Maven repository (Jahia
public/snapshot Nexus, or `mvn install` of mcp-server locally).

## Tests

- **Unit** (`src/test/java`): whitelist gate, admin gate, in-process GraphQL client (status, UTF-8),
  each tool with a mocked `ToolContext` (`tools/ToolContextFixture`), skill-name validation.
- **Cypress** (`tests/`): calls `/modules/mcp` through `tests/cypress/support/mcp.ts`; provisioning
  installs `mcp-server`. Non-admin coverage uses HTTP Basic with a user created by `createUser`.

```bash
cd tests && cp .env.example .env && yarn install && ./ci.build.sh && ./ci.startup.sh
```

## Configuration files

| File | Purpose |
|---|---|
| `org.jahia.community.mcp.cfg` | Persists `whitelist` as comma-separated dot-paths |
