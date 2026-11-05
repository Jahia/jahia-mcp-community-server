# CLAUDE.md — Jahia MCP Community Server

## What this module does

An extension of Jahia's `mcp-server` module (0.5.x). This module is version 2.x. It contributes four MCP tools to the `mcp-server`
endpoint (`/modules/mcp`) through its public tool SPI: `community.graphql.execute`,
`community.graphql.introspect`, `community.skill.list` and `community.skill.get`. The module owns no
endpoint and no MCP SDK: `mcp-server` handles transport, authentication, JSON-RPC and the tool catalog.

Every community tool is administrator-only. `community.graphql.execute` is additionally gated by a
whitelist that is read-only by default (queries allowed except under `admin`, mutations and subscriptions
refused). Read-only is not non-sensitive: every other query returns what the administrator can read
(JCR-stored settings such as the mail server configuration, user data) to the AI client, so the docs
recommend an explicit allow list in production. Skills (Markdown instruction documents) are stored in JCR.

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
state (`JCRSessionFactory` current user, locale) and cleans up after the call. This binding is an
mcp-server implementation detail, not part of its tool SPI (which tells tools to use `ToolContext`, not
thread-locals). The module depends on it upstream: if mcp-server stops binding the caller this way, every
community tool denies (fail closed). Check it on every mcp-server upgrade (`07-mcpCallerIdentity`).

- **Never** call `JcrSessionFilter.endRequest()` or `JCRSessionFactory.setCurrentUser(...)` to change the
  caller inside a tool. The only exception is `InternalGraphQLClient.execute`, which sets the bound
  caller for the servlet call and restores the previous user in `finally`.
- **Never** gate on `PermissionService.hasPermission(...)` inside a tool. No security-filter scope is bound
  on the worker thread, and `PermissionServiceImpl` returns `true` when scopes are null.
- The admin gate is `CommunityMcpAccess.requireAdministrator`. It takes the bound user from
  `CommunityMcpAccess.boundCaller(ctx)`, which requires that user's username to equal `ctx.username()`.
  It then opens a JCR session for that `JahiaUser` (its realm included) and checks
  `session.getRootNode().hasPermission("admin")`. Any missing binding, mismatch or repository error denies.
- The tools also require an explicit `Authorization` header on the MCP request (API token or HTTP Basic).
  A call authenticated only by a session cookie is refused with `forbidden`, so a cross-site form post
  cannot drive them. mcp-server's own tools are outside this module and keep whatever cookie handling
  mcp-server has (an upstream cross-site request risk).
- API-scope checks of graphql-dxm-provider do not apply to the in-process GraphQL call (see Accepted
  risks). JCR ACLs do.

### GraphQL dispatch (InternalGraphQLClient)

- `execute(ctx, query, variables)` requires `boundCaller(ctx)` (throws `IllegalStateException` otherwise),
  sets it as the current user, dispatches, and restores the previous user in `finally`.
- The request is a synthetic JSON POST: an `HttpServletRequestWrapper` with a no-op proxy delegate. Only
  `Content-Type` is exposed as a header. No `Authorization` header is forwarded.
- The response is captured by a wrapper that records `setStatus`, `sendError` and `getStatus`. The output
  stream and the writer share one UTF-8 byte buffer.
- The buffer is capped at `MAX_RESPONSE_BYTES` = 5 MB. Beyond it the bytes are dropped, the overflow flag
  is set, and `execute` fails. The tool returns `validation` with a hint to narrow the query (select fewer
  fields, paginate), never "retry": the same document would overflow again.
- The servlet is injected with a target on `(component.name=graphql.kickstart.servlet.OsgiGraphQLHttpServlet)`,
  as an OPTIONAL, GREEDY reference. When it is missing (provider stopped, component renamed), the client logs a
  WARN naming the target and `execute` throws `ServletUnavailableException`; execute and introspect stay in
  `tools/list` and answer `unavailable` (`ToolSupport.graphQLUnavailable`), never an NPE or a "retry" hint.
  The component is reactivated when the servlet appears.

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
- `saveSkill` and `deleteSkill` (GraphQL) return `true` or throw a client-safe `DataFetchingException`
  (invalid name, content over `MAX_CONTENT_CHARS` = 200,000, unknown skill, folder or non-skill node,
  repository failure). They never return `false` and never echo the key.
- `saveSkill` creates missing intermediate `jnt:contentList` folders. A segment that is already a skill
  node cannot become a folder. An existing node must be `mcp:skill`, otherwise the call is refused.
- Reads, writes and seeding run in a system session (`doExecuteWithSystemSession`). The container
  `mcp-skills` has its ACL inheritance broken, so no ordinary role can write it through generic paths.
  Only the admin-gated GraphQL operations and the module's own seeding write skills. `ensureContainer` runs
  on every activation and breaks the inheritance of an existing (1.x) container too. It also removes the local
  role grants (ACL entries) on the container and every node below it (`purgeLocalGrants`, 1.x upgrade hardening,
  activation only); it is idempotent.
  It never changes skill content: 1.x skills must be reviewed by an administrator (README upgrade notes).
- Listing uses JCR-SQL2 `ISDESCENDANTNODE` under the skills root.
- The `mcp:` CND prefix is generic. It is kept for compatibility; a rename is a breaking JCR change.

### Access control (GraphQLWhitelistGate)

Configuration (`McpConfigService`, PID `org.jahia.community.mcp`):

- DS component with `configurationPolicy = REQUIRE` and a metatype (`Config.whitelist()`, `String[]`).
  The shipped default `.cfg` provides the configuration. Without it the component, and the gate and the
  execute tool that reference it, are not active. There is no default in code.
- A CSV value and an array both arrive as `String[]` and are joined and parsed as one list.
- The current state is one immutable `WhitelistSnapshot` (`raw`, `normalized`, `maxDepth`), replaced
  atomically on `@Modified`. The gate reads one snapshot per call.
- A configuration update that cannot be read keeps the previous snapshot (ERROR log). One that cannot be
  read at activation installs a deny-everything snapshot until it is fixed. Never fall back to empty: that
  is the read-only default, wider than most explicit lists.
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
- Privilege-granting mutations are never allowed either: `grantRoles` and `revokeRoles` at any depth (for
  example `mutation.jcr.mutateNode.grantRoles`) and `mutation.admin.personalApiTokens`. Their denial says
  the operation `can never be run`, like the paths above. A broad entry such as `mutation:jcr` would
  otherwise let a prompt-injected session escalate a user or mint a long-lived token.
- Internal form puts the operation type first: `query.jcr.nodeByPath`, `mutation.jcr.deleteNode`.
  Denial messages and logs use the user-facing form (`display(...)`).

Decision (`GraphQLWhitelistGate.check`):

1. Parse with `graphql.parser.Parser`. Failure denies with `could not be parsed`, always, with the line and
   column of the syntax error (never the parser message, which can quote the document).
2. If the document has a named fragment spread, deny, in every state, read-only default included.
   The gate does not expand spreads, so their content cannot be checked. Inline fragments are supported.
3. Extract operation-qualified paths for every operation in the document, up to `maxDepth` segments.
   Inline fragments keep the parent's prefix and depth.
4. Drop paths with an `__`-prefixed segment (introspection passes).
5. For each remaining path:
   - never-allowed (including privilege-granting mutations): deny;
   - read-only default: `query.admin` and everything under it denies (the reason names `admin` and
     `query:admin`: the namespace exposes OSGi configuration values, secrets included); any other `query.*`
     passes; `mutation.*` denies with the read-only message; `subscription.*` denies; anything else denies;
   - otherwise the path passes if an entry covers it (`pathCoveredBy`: equal or below) or is a container of
     a more specific entry (`pathIsContainerOf`). Otherwise deny with `is not in the whitelist`.
6. Each denial logs WARN with path, reason, user and IP. Named-spread and parse denials log without a path.

Denial strings are client-safe and are returned with `ToolError`. The hint is chosen by
`GraphQLExecuteTool.hintForDenial` from the denial text, in this order: `could not be parsed`,
`named fragment`, `can never be run`, `subscriptions are not supported` or `'subscription:`,
`mutations require an explicit` or `'mutation:`; anything else (the `admin` query denial included) gets the
generic whitelist hint. The never-allowed check must come before `'mutation:`. Keep these substrings in sync
with the denial texts of `GraphQLWhitelistGate`, or the hints go wrong.

### React admin UI

- `Administration → MCP Server`, registered under `administration-server-configuration` (`register.jsx`,
  `requiredPermission: 'admin'`).
- The allow-list is a WAI-ARIA tree with roving tabindex. Operation roots are loaded in two documents, one
  for Query and one for Mutation, because of the bad-faith guard. Child fields load per type when a node is
  expanded, with a retry on error.
- Queries and mutations are separate groups with bulk actions. Every edit that empties a non-empty list
  (Clear, Clear queries/mutations, unchecking the last item, removing the last entry) is confirmed first
  (`edit()` in `CommunityMcpConfig.jsx`), because an empty list restores the read-only default. Clearing
  queries while mutations remain and allowing all mutations are confirmed too. Discard is never confirmed.
  "Allow all queries" checks every query root except `admin` (it exposes configuration values, secrets
  included), matching the read-only default. Checking `query:admin` or a field under it is confirmed every
  time.
- While a `ConfirmDialog` is open, the rest of the page is `inert`; the attribute is removed before focus
  returns to the opener.
- `communityMcp { settings { clustered } }` tells the UI whether Jahia runs as a cluster; it then shows
  `clusterNote` (saving changes the allow list of the serving node only) and, after a save,
  `saveSuccessCluster` instead of the plain success message.
- The policy banner (`policyOf`) shows the policy in force (`policyInForce`, the saved list) and, with
  unsaved changes, the policy after save (`policyPendingPrefix`).
- Session expiry: a 401 or 403 on load or save shows `loadSessionExpired` / `saveSessionExpired` instead of
  the generic error. Unsaved edits are kept in `sessionStorage` (`draft.js`) with the server list they were
  made against, and restored after re-login only if the server still holds that list; otherwise the draft
  is dropped.
- Operation paths are `query:<name>…` / `mutation:<name>…` (`operationTree.js`). Legacy entries are shown
  as `query:` entries and the form starts dirty, so it can be saved in the new form.
- Locked: the never-allowed paths cannot be checked: `mutation:communityMcp`,
  `mutation:admin.jahia.configuration`, `mutation:admin.personalApiTokens`, and any mutation path with a
  `grantRoles` or `revokeRoles` segment. The rules live in `policyRules.js`, which mirrors `WhitelistEntries`:
  keep both in sync.
- Problem entries (`findProblemEntries`), each with Remove: locked, root not in the schema, and
  invalid (another operation type or malformed: the server ignores them). Invalid entries are kept, never
  dropped. A list holding only invalid entries shows the "everything refused" banner (`policyOf`).
- Save calls `communityMcp { saveSettings(whitelist) }`. It is blocked client-side, with a message naming
  the entries, while invalid or locked entries remain (`findBlockingEntries`). Server GraphQL errors are
  displayed with the server's message. Save is never `disabled`, so focus stays put.
- The save bar is at the end of the panel and is NOT sticky (a sticky bar hides the focused control,
  WCAG 2.4.12). Focus rules: after a confirmed emptying edit, the panel heading; after Discard or Dismiss,
  Save; after Remove, the next (else previous) entry of the same list, else the list summary or heading.

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

| Topic | Risk | Decision |
|---|---|---|
| API scopes | Security-filter API scopes of graphql-dxm-provider are not applied to in-process calls | Accepted. Tools are admin-only; JCR ACLs and the admin gate are the boundary |
| Prefix entries | A prefix entry grants everything below it (for example `mutation:jcr`) | Accepted and documented. Prefer exact paths |
| Deny list | Self-protection is a deny list (two mutation paths and the privilege-granting mutations); other state-changing mutations (module, bundle, provisioning, from any module) are guarded only by the admin's entries | Accepted and documented in the README security notes |
| Skills under `mutation:jcr` | A `root` caller with `mutation:jcr` whitelisted can rewrite skills through the generic JCR mutations (root bypasses the container ACL) | Accepted and documented. Policing it needs argument inspection |
| Response cost | The 5 MB response cap applies after the query ran and was serialized; no timeout or complexity limit | Accepted (admin-only) and documented |
| Read-only exposure | The read-only default lets every query outside `admin` run, JCR-stored settings and user data included, and sends the result to the AI client | Kept as the default; README, `.cfg` and CHANGELOG recommend an explicit allow list in production |
| Snapshot build | Built against mcp-server 0.5.0-SNAPSHOT; the SPI version range is tight | Release only against a released mcp-server 0.5.x |
| Caller binding (upstream) | The admin gate and the GraphQL call rely on mcp-server binding the caller on the worker thread, which the SPI does not guarantee | Documented upstream dependency; fails closed if it changes |
| SPI versioning (upstream) | mcp-server exports the SPI package at its bundle version, so `[0.5,0.6)` leaves the whole bundle unresolved on mcp-server 0.6 even with an unchanged SPI | Documented upstream dependency; release a new version per mcp-server minor |
| Upstream cross-site requests | mcp-server's own tools may accept cookie-authenticated calls | Outside this module; the community tools require an `Authorization` header |
| `mcp:` CND prefix | Generic prefix | Kept; renaming it is a breaking JCR change |

## Build

```bash
mvn clean install          # Java unit tests (JUnit 5) + JaCoCo check + React (webpack) build + bundle
yarn test                  # Jest: NOT run by Maven (the frontend plugin only runs yarn build:production)
```

Coverage gates: JaCoCo `check` is bound to `verify` and fails below 90% line coverage of the bundle (pom.xml).
Jest fails below the `coverageThreshold` of `jest.config.js` (95% statements, lines, functions; 90% branches).
Add tests with the code, never lower a threshold to pass.

Requires `org.jahia.modules:mcp-server:0.5.0-SNAPSHOT` in a reachable Maven repository (Jahia public or
snapshot Nexus, or `mvn install` of mcp-server locally). The `jahia-depends` value is
`default,graphql-dxm-provider=[3.9,4),mcp-server=[0.5,0.6)`. graphql-dxm-provider is compiled against 3.9.1 and
imported as `[3.9,4)`: the version built against sets the import range, so bump it together with the
minimum supported provider version.

## Tests

- **Unit** (`src/test/java`): whitelist parsing and gate decisions (`WhitelistEntries`, `GraphQLWhitelistGate`),
  the administrator gate and bound-caller rules (`CommunityMcpAccess`), the in-process client (status, UTF-8,
  5 MB cap, caller restore), each tool with a mocked `ToolContext` (`tools/ToolContextFixture`), the
  template method (`AbstractCommunityToolTest`), the GraphQL settings and skill mutations, skill-name
  validation and skill service behaviour.
- **Jest** (`src/javascript/CommunityMcpConfig/__tests__`): tree and allow-list helpers and the component.
- **Cypress** (`tests/`): calls `/modules/mcp` through `tests/cypress/support/mcp.ts`. Provisioning installs
  `mcp-server`. Non-admin coverage uses HTTP Basic with a user created with `createUser`.
  The configuration is applied asynchronously (DS `@Modified`): write the whitelist with `setWhitelist`
  (or wait with `waitForWhitelist`) before asserting, and check "unchanged" with
  `expectWhitelistUnchanged`, which also reads the value stored in ConfigurationAdmin (written before the
  call returns), so an accepted-but-not-yet-applied write cannot pass for a refusal.
  `support/e2e.js` resets the whitelist to empty before every spec. The suite needs its own Jahia: the
  whitelist is server-wide and token, skill and probe names are fixed. Test names and comments describe
  behaviour, never tracker ids.

```bash
cd tests && cp .env.example .env && yarn install && ./ci.build.sh && ./ci.startup.sh
```

## Configuration files

| File | Purpose |
|---|---|
| `org.jahia.community.mcp.cfg` (shipped default in `META-INF/configurations`) | Persists `whitelist` as comma-separated `query:<path>` / `mutation:<path>` entries. Written by `communityMcp { saveSettings }` |
