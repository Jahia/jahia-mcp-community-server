# CLAUDE.md — Jahia MCP Community Server

## What this module does

An extension of Jahia's `mcp-server` module (0.5.x). This module is version 2.x. It contributes four MCP tools to the `mcp-server`
endpoint (`/modules/mcp`) through its public tool SPI: `community.graphql.execute`,
`community.graphql.introspect`, `community.skill.list` and `community.skill.get`. The module owns no
endpoint and no MCP SDK: `mcp-server` handles transport, authentication, JSON-RPC and the tool catalog.

Every community tool is administrator-only. `community.graphql.execute` is additionally gated by a
whitelist that is read-only by default (queries allowed, mutations and subscriptions refused). Skills
(Markdown instruction documents) are stored in JCR.

## Source layout

```
src/main/java/org/jahia/community/mcp/
    McpSkillService.java                   # DS component (root package): JCR CRUD and seeding of mcp:skill nodes
    access/
        CommunityMcpAccess.java            # Administrator gate (JCR 'admin' on /) and bound-caller lookup
        GraphQLWhitelistGate.java          # Operation-qualified whitelist gate (graphql-java parser, AST walk)
    config/
        McpConfigService.java              # DS component, configurationPolicy=REQUIRE, metatype (ocd Config)
        WhitelistEntries.java              # Entry syntax: query:<path> / mutation:<path>; legacy = query-only
        WhitelistSnapshot.java             # Immutable snapshot published atomically by McpConfigService
    tools/
        AbstractCommunityTool.java         # Template: final execute() = audit(adminGate(doExecute))
        AbstractSkillTool.java             # Read-only skill tools, tags reads-ugc
        GraphQLExecuteTool.java            # community.graphql.execute    (DESTRUCTIVE)
        GraphQLIntrospectTool.java         # community.graphql.introspect (READ_ONLY)
        SkillListTool.java                 # community.skill.list        (READ_ONLY)
        SkillGetTool.java                  # community.skill.get         (READ_ONLY)
        InternalGraphQLClient.java         # In-process call of OsgiGraphQLHttpServlet, bounded response
        ToolSupport.java                   # Schema loading, audit recording, hashing, sanitizing
    graphql/                               # ONLY exported package (Export-Package)
        McpGraphQLExtensionsProvider.java  # DXGraphQLExtensionsProvider (bundle-scan registration)
        McpQueryExtension.java             # Adds `communityMcp` to Query  → CommunityMcpQuery
        McpQuery.java                      # settings, skills; types CommunityMcpSettings, CommunityMcpSkill
        McpMutationExtension.java          # Adds `communityMcp` to Mutation → CommunityMcpMutation
        McpMutation.java                   # saveSettings, saveSkill, deleteSkill

src/main/resources/
    schemas/community.*.json               # Hand-written JSON Schemas (draft 2020-12), one per tool
    META-INF/definitions.cnd               # mcp:skill node type; mcpmix:mcp (droppable, hidden from pickers)
    META-INF/configurations/org.jahia.community.mcp.cfg   # Default whitelist (empty); first line is fixed
    skills/default/*.md                    # Default skills, seeded into JCR when absent
    javascript/locales/{en,fr}.json        # Admin UI strings

src/javascript/CommunityMcpConfig/         # React admin UI (allow-list tree, save bar, confirmations)
tests/                                     # Cypress Docker integration tests (provision mcp-server)
```

Package rule: only `org.jahia.community.mcp.graphql` is exported. Everything else is private to the
bundle. graphql-dxm-provider reflects over the annotated extension classes in that package.

## Architecture

### Tools (mcp-server SPI)

- Each tool is a DS component `@Component(service = JahiaMcpTool.class, immediate = true)` extending
  `AbstractCommunityTool`. mcp-server's `ToolRegistry` binds it dynamically.
- Names are `community.*`, so they never collide with the built-in tools.
- `execute()` is `final` in `AbstractCommunityTool`: it runs the administrator gate, then `doExecute`
  (only for administrators), then records the audit event. A subclass cannot skip the gate or the audit.
  Do not override `execute()`; implement `doExecute` and, if needed, `auditDetails` (never content).
- Input schemas load in the constructor from `schemas/<toolName>.json`. A missing resource fails that
  one component with a clear message.
- Declare `safety()` explicitly: `community.graphql.execute` is `DESTRUCTIVE`, the others `READ_ONLY`.
  Tags: `community` on every tool; `destructive` and `reads-ugc` on execute; `reads-ugc` on the skill tools.
- Recoverable failures return `ToolResult.error(code, message, hint)` with `ToolError.CODE_*`. Never echo
  exception messages to the client; log them. Echoed caller or server text goes through `ToolSupport.sanitize`.

### Threading — read before touching auth or GraphQL dispatch

mcp-server runs tools on Reactor worker threads. Around each call it binds the caller on the JCR thread
state (`JCRSessionFactory` current user, locale) and cleans up after the call.

- **Never** call `JcrSessionFilter.endRequest()` or `JCRSessionFactory.setCurrentUser(...)` to change the
  caller inside a tool. The only exception is `InternalGraphQLClient.execute`, which sets the bound
  caller for the servlet call and restores the previous user in `finally`.
- **Never** gate on `PermissionService.hasPermission(...)` inside a tool. No security-filter scope is bound
  on the worker thread, and `PermissionServiceImpl` returns `true` when scopes are null.
- The admin gate is `CommunityMcpAccess.requireAdministrator`. It takes the bound user from
  `CommunityMcpAccess.boundCaller(ctx)`, which requires that user's username to equal `ctx.username()`.
  It then opens a JCR session for that `JahiaUser` (its realm included) and checks
  `session.getRootNode().hasPermission("admin")`. Any missing binding, mismatch or repository error denies.
- API-scope checks of graphql-dxm-provider do not apply to the in-process GraphQL call (SEC-4, see
  Accepted risks). JCR ACLs do.

### GraphQL dispatch (InternalGraphQLClient)

- `execute(ctx, query, variables)` requires `boundCaller(ctx)` (throws `IllegalStateException` otherwise),
  sets it as the current user, dispatches, and restores the previous user in `finally`.
- The request is a synthetic JSON POST: an `HttpServletRequestWrapper` with a no-op proxy delegate. Only
  `Content-Type` is exposed as a header. No `Authorization` header is forwarded (SEC-10).
- The response is captured by a wrapper that records `setStatus`, `sendError` and `getStatus`. The output
  stream and the writer share one UTF-8 byte buffer.
- The buffer is capped at `MAX_RESPONSE_BYTES` = 5 MB. Beyond it the bytes are dropped, the overflow flag
  is set, and `execute` throws `IOException`. Callers return `internal`.
- The servlet is injected with a target on `(component.name=graphql.kickstart.servlet.OsgiGraphQLHttpServlet)`.
  If that name changes, execute and introspect silently disappear from `tools/list`.

### introspect — bounded discovery

- Jahia's bad-faith introspection guard rejects any document with more than one `__Type.fields` selection.
  Each type is therefore fetched in its own request, with the name passed as a GraphQL variable
  (`query($name: String!) { __type(name: $name) … }`). Never format `typeNames` into a document.
- No arguments: a `__schema` summary (types reduced to `{name, kind}`, introspection types excluded) and
  the root Query and Mutation details.
- `typeNames`: 1 to `MAX_TYPE_NAMES` = 25 names matching the GraphQL name pattern. No schema step.
- Output budget `MAX_RESULT_CHARS` = 100,000 characters for type details. The first type is always
  included. Names that do not fit go to `truncatedTypes`; unknown or failed types go to `skippedTypes`.

### Skills mechanism

- `McpSkillService` (`@Component(immediate=true)`) seeds `skills/**/*.md` into
  `/sites/systemsite/contents/mcp-skills/` on activation. It creates a node only when it is absent, so bundled
  edits never update an existing install. Frontmatter: `mcpName` (stored as `jcr:title`), `description`.
- Node type `mcp:skill`. `SkillEntry.path` is the key relative to the skills root (`default/hello-jahia`);
  `community.skill.list` returns it as `name`, and `getSkill`, `saveSkill` and `deleteSkill` accept it.
  `SkillEntry.name` is the JCR node name and is not a key.
- Names: `SKILL_NAME_PATTERN` (segments of `[A-Za-z0-9_-]` separated by single `/`), no empty, `.` or `..`
  segments, then `assertDescendant` on the resolved path.
- `saveSkill` creates missing intermediate `jnt:contentList` folders. A segment that is already a skill
  node cannot become a folder. An existing node must be `mcp:skill`, otherwise the call is refused.
- Reads, writes and seeding run in a system session (`doExecuteWithSystemSession`). The container
  `mcp-skills` has its ACL inheritance broken, so no ordinary role can write it through generic paths.
  Only the admin-gated GraphQL operations and the module's own seeding write skills. `ensureContainer` runs
  on every activation and breaks the inheritance of an existing (1.x) container too; it is idempotent.
- Listing uses JCR-SQL2 `ISDESCENDANTNODE` under the skills root.
- The `mcp:` CND prefix is generic. It is kept for compatibility (ARCH-18 deferred); a rename is a
  breaking JCR change.

### Access control (GraphQLWhitelistGate)

Configuration (`McpConfigService`, PID `org.jahia.community.mcp`):

- DS component with `configurationPolicy = REQUIRE` and a metatype (`Config.whitelist()`, `String[]`).
  The shipped default `.cfg` provides the configuration. Without it the component, and the gate and the
  execute tool that reference it, are not active. There is no default in code.
- A CSV value and an array both arrive as `String[]` and are joined and parsed as one list.
- The current state is one immutable `WhitelistSnapshot` (`raw`, `normalized`, `maxDepth`), replaced
  atomically on `@Modified`. The gate reads one snapshot per call.
- `saveWhitelist` validates every entry first. A malformed or never-allowed entry throws
  `IllegalArgumentException` naming the entry, and nothing is written. Otherwise it writes the
  `whitelist` key through `ConfigurationAdmin`.
- `saveWhitelist` writes the configuration of the node that served the request only. In a cluster the
  whitelist must be set on every node (or through provisioning or the `.cfg`). Nodes do not replicate it.
- Upgrades: an installation from 1.x keeps its existing `.cfg` (the shipped file is a
  "default configuration" and is not overwritten), including its stale 1.x comment that an empty
  whitelist allows everything. An empty whitelist is the read-only default.

Entry syntax (`WhitelistEntries`):

- `query:<path>` or `mutation:<path>`, with `<path>` = `name(.name)*` of GraphQL names. The prefix is
  case-insensitive.
- An unprefixed entry is a legacy query-only entry. Mutations must always be explicit.
- A deeper mutation entry also lets its intermediate mutation fields run. `mutation:jcr.addNode.mutateProperty`
  also lets `mutation.jcr.addNode` execute, because GraphQL cannot run the deeper field without it. Choose
  entries whose intermediate fields may run (for example `addNode`, `copyNode`, `moveNode` under `jcr` create
  or move nodes), and prefer the leaf paths you need.
- Malformed entries and other operation types (for example `subscription:`) normalize to nothing.
  `McpConfigService` logs legacy and malformed entries at WARN when the configuration loads.
- Never-allowed: `mutation.communityMcp` and `mutation.admin.jahia.configuration`, each with everything
  under it. The gate refuses them in every state, including when a broader entry such as `mutation:admin`
  covers them, and `saveWhitelist` refuses entries for them. The second path is needed because the
  generic `configuration(pid, …)` mutation under `admin` could rewrite this module's own configuration.
- Internal form puts the operation type first: `query.jcr.nodeByPath`, `mutation.jcr.deleteNode`.
  Denial messages and logs use the user-facing form (`display(...)`).

Decision (`GraphQLWhitelistGate.check`):

1. Parse with `graphql.parser.Parser`. Failure denies with `could not be parsed`, always.
2. If the document has a named fragment spread, deny (SEC-2), in every state, read-only default included.
   The gate does not expand spreads, so their content cannot be checked. Inline fragments are supported.
3. Extract operation-qualified paths for every operation in the document, up to `maxDepth` segments.
   Inline fragments keep the parent's prefix and depth.
4. Drop paths with an `__`-prefixed segment (introspection passes).
5. For each remaining path:
   - never-allowed: deny;
   - read-only default: `query.*` passes; `mutation.*` denies with the read-only message; `subscription.*`
     denies; anything else denies;
   - otherwise the path passes if an entry covers it (`pathCoveredBy`: equal or below) or is a container of
     a more specific entry (`pathIsContainerOf`). Otherwise deny with `is not in the whitelist`.
6. Each denial logs WARN with path, reason, user and IP. Named-spread and parse denials log without a path.

Denial strings are client-safe and are returned as `forbidden` with `ToolError`. The hint is chosen by
`GraphQLExecuteTool.hintForDenial`: keep the substrings `could not be parsed`, `named fragment`,
`read-only`, `'mutation:` and `'mutation.` in sync with the gate, or the hints go wrong.

### React admin UI

- `Administration → MCP Server` (`register.jsx`, `requiredPermission: 'admin'`).
- The allow-list is a WAI-ARIA tree with roving tabindex. Operation roots are loaded in two documents, one
  for Query and one for Mutation, because of the bad-faith guard. Child fields load per type when a node is
  expanded, with a retry on error.
- Queries and mutations are separate groups with bulk actions. Clearing the list, clearing queries while
  mutations remain, and allowing all mutations go through a confirmation dialog.
- Operation paths are `query:<name>…` / `mutation:<name>…` (`operationTree.js`). Legacy entries are shown
  as `query:` entries and the form starts dirty, so it can be saved in the new form.
- Locked: `mutation:communityMcp` cannot be checked. Entries that are locked or whose root is not in the
  schema are listed as problem entries with Remove.
- Save calls `communityMcp { saveSettings(whitelist) }`. Server GraphQL errors are displayed with the
  server's message. Save is never disabled, so focus stays put.

## Key implementation constraints

- mcp-server SPI is pre-1.0: `Import-Package: org.jahia.modules.mcp.spi.tool;version="[0.5,0.6)"`. Bump the
  range deliberately, with a new mcp-server minor. Jackson types come from the platform.
- `org.slf4j` is imported as `[1.7,3)` to resolve against Pax Logging on Jahia 8.2.
- Skill display name is `jcr:title` (`mix:title`), exposed as `mcpName`.
- Default `.cfg` files must start with `# default configuration - won't be overridden`. The whitelist
  `.cfg` keeps `whitelist=` empty.
- GraphQL extensions live under ONE namespace (`communityMcp`). Never add flat root fields.
- GraphQL type names are global: `CommunityMcpQuery`, `CommunityMcpMutation`, `CommunityMcpSettings`,
  `CommunityMcpSkill`. A duplicate name breaks the whole schema.
- The `src/main/import` initial content is removed: the module does not ship JCR import content.
  Skills are seeded by code.
- `mcpmix:mcp` carries `jmix:droppableContent` (the `jnt:contentList` container and folders only accept
  droppable children) and `jmix:hiddenType` (keeps the types out of the editors' content-creation pickers).
  Keep both. Do not introduce a custom folder type: the container and folders are `jnt:contentList` as 1.x
  created them, so upgrades need no migration. Editing is restricted by the container ACL, not by the type.

## Accepted risks

| ID | Risk | Decision |
|---|---|---|
| SEC-4 | Security-filter API scopes of graphql-dxm-provider are not applied to in-process calls | Accepted. Tools are admin-only; JCR ACLs and the admin gate are the boundary |
| SEC-6 | A prefix entry grants everything below it (for example `mutation:jcr`) | Accepted and documented. Prefer exact paths |
| ARCH-7 | Built against mcp-server 0.5.0-SNAPSHOT; the SPI version range is tight | Release only against a released mcp-server 0.5.x |
| ARCH-18 | Generic `mcp:` CND prefix | Kept; renaming it is a breaking JCR change |

## Build

```bash
mvn clean install          # Java unit tests (JUnit 5) + Jest + React build + bundle
```

Requires `org.jahia.modules:mcp-server:0.5.0-SNAPSHOT` in a reachable Maven repository (Jahia public or
snapshot Nexus, or `mvn install` of mcp-server locally). The `jahia-depends` value is
`default,graphql-dxm-provider,mcp-server=[0.5,0.6)`.

## Tests

- **Unit** (`src/test/java`): whitelist parsing and gate decisions (`WhitelistEntries`, `GraphQLWhitelistGate`),
  the administrator gate and bound-caller rules (`CommunityMcpAccess`), the in-process client (status, UTF-8,
  5 MB cap, caller restore), each tool with a mocked `ToolContext` (`tools/ToolContextFixture`), the
  template method (`AbstractCommunityToolTest`), the GraphQL settings and skill mutations, skill-name
  validation and skill service behaviour.
- **Jest** (`src/javascript/CommunityMcpConfig/__tests__`): tree and allow-list helpers and the component.
- **Cypress** (`tests/`): calls `/modules/mcp` through `tests/cypress/support/mcp.ts`. Provisioning installs
  `mcp-server`. Non-admin coverage uses HTTP Basic with a user created with `createUser`.

```bash
cd tests && cp .env.example .env && yarn install && ./ci.build.sh && ./ci.startup.sh
```

## Configuration files

| File | Purpose |
|---|---|
| `org.jahia.community.mcp.cfg` (shipped default in `META-INF/configurations`) | Persists `whitelist` as comma-separated `query:<path>` / `mutation:<path>` entries. Written by `communityMcp { saveSettings }` |
