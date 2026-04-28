# CLAUDE.md — Jahia MCP Community Server

## What this module does

Exposes Jahia's GraphQL API as a stateless MCP (Model Context Protocol) HTTP server. AI clients (Claude Code) call four tools — `introspectSchema`, `executeGraphQL`, `listSkills`, and `getSkill` — over JSON-RPC 2.0. Access is gated by an API-token permission check and an optional dot-path whitelist. Skills (Markdown instruction documents) are stored in JCR and delivered dynamically to MCP clients.

## Source layout

```
src/main/java/org/jahia/community/mcp/
    McpServlet.java                        # Main servlet + MCP transport + all four tools
    McpSkillService.java                   # OSGi component — JCR CRUD for mcp:skill nodes
    config/
        McpConfigService.java              # OSGi ManagedService — reads whitelist from .cfg
    graphql/
        McpGraphQLExtensionsProvider.java  # Registers GraphQL extensions
        McpQueryExtension.java             # mcpSettings + mcpSkills queries
        McpMutationExtension.java          # mcpSaveSettings + mcpSaveSkill + mcpDeleteSkill

src/javascript/McpConfig/
    McpConfig.jsx                          # React admin UI (lazy-loaded operation tree)
    McpConfig.gql.js                       # Apollo GQL documents
    McpConfig.scss                         # CSS Modules styles

src/main/resources/
    META-INF/
        definitions.cnd                    # mcp:skill node type definition
        configurations/
            org.jahia.community.mcp.cfg        # Default OSGi config (whitelist=)
            org.jahia.bundles.api.authorization-mcp.yml  # Grants mcp permission to admin role
            org.jahia.modules.PersonalApiToken-mcp.cfg   # Binds token scope to /modules/community-mcp
    javascript/locales/en.json             # UI translation strings
    skills/                                # Default skills seeded into JCR on activation
        default/
            hello-jahia.md                 # Default hello-jahia skill
            jahia-properties.md            # Jahia 8.2 properties quick guide
            jahia-properties-ref.md        # Jahia 8.2 full properties reference

tests/                                     # Cypress Docker integration tests
    cypress/e2e/
        01-mcpSettings.cy.ts               # Settings GraphQL API
        02-mcpEndpoint.cy.ts               # MCP endpoint access control
        03-mcpSkills.cy.ts                 # Skills GraphQL API + MCP listSkills/getSkill tools
```

## Architecture

### Servlet (McpServlet)

- OSGi `@Component(service={HttpServlet,Servlet}, property={"alias=/community-mcp","allow-api-token=true"})`
- Implements `McpStatelessServerTransport` — each POST is a complete, independent JSON-RPC exchange
- On `@Activate`, builds `McpStatelessSyncServer` with four tools; sets the thread's context classloader to the bundle's own classloader before building (required for the MCP SDK's Jackson init)
- Four OSGi `@Reference` injections: `PermissionService`, `OsgiGraphQLHttpServlet` (target filter), `McpConfigService`, `McpSkillService`

### Request flow (doPost)

1. `permissionService.hasPermission("community-mcp")` — returns **401** if false
2. Reads body, extracts `Authorization` header + current `JahiaUser` + client IP → `McpTransportContext`
3. Deserializes JSON-RPC message and delegates to `mcpHandler`
4. For `tools/call → executeGraphQL`: calls `checkAccess(query, user, ip)` before forwarding to GQL servlet

### GraphQL dispatch (executeGraphQL)

Uses `McpHttpServletRequestWrapper` / `McpHttpServletResponseWrapper` (inner classes) to fake an HTTP request/response pair so `OsgiGraphQLHttpServlet.service()` can be called in-process. The `Authorization` header and `JahiaUser` are forwarded, preserving all JCR ACL checks.

### introspectSchema — multi-step workaround

Jahia's bad-faith introspection guard blocks any GraphQL document containing more than one `__Type.fields` selection. `introspectSchema` works around this with two passes:

1. **Step 1** — one request: `__schema { queryType mutationType types { name kind } }` — gets all type names
2. **Step 2** — one request per type: `__type(name: $typeName) { fields { name ... } }` — each has exactly one `__Type.fields`

### Skills mechanism

Skills are `mcp:skill` JCR nodes stored under `/sites/systemsite/contents/mcp-skills/`. They can be organized at any depth in sub-folders.

**`McpSkillService`** is an OSGi `@Component(immediate=true)` that:
- On `@Activate(BundleContext)`: scans `skills/` in the bundle using `bundle.findEntries("skills", "*.md", true)`, parses YAML frontmatter (`mcpName`, `description`) and body (content), and creates JCR nodes for any that do not already exist. Folder hierarchy mirrors the JCR sub-folder structure.
- `listSkills()`: JCR-SQL2 query — `SELECT * FROM [mcp:skill] WHERE ISDESCENDANTNODE(skill, '<SKILLS_PATH>') ORDER BY NAME(skill)` — finds skills at any depth
- `getSkill(name)`: direct path lookup at `SKILLS_PATH + "/" + name`
- `saveSkill(name, mcpName, description, content)` / `deleteSkill(name)`: system JCR session CRUD

**Skill file format** (`src/main/resources/skills/<subfolder>/<name>.md`):
```markdown
---
mcpName: Display Name
description: Short description
---
Full Markdown content here.
```

**MCP tools**:
- `listSkills` — returns `[{name, mcpName, description}]` JSON array
- `getSkill(name)` — returns raw Markdown content; `name` may include sub-folder path (e.g. `default/hello-jahia`)

**GraphQL API** (all require `admin` permission):
- `mcpSkills` query: `{ name, mcpName, description, content }`
- `mcpSaveSkill(name!, mcpName, description, content!)` mutation
- `mcpDeleteSkill(name!)` mutation

### JCR node type (definitions.cnd)

```
[mcp:skill] > jnt:content, mcpmix:mcp, mix:title
 - mcp:description (string)
 - mcp:content (string, textarea)
```

`mix:title` provides `jcr:title` as the display name property (mapped to `mcpName` in Java/GraphQL). `mcpmix:mcp` is a module mixin combining `jmix:accessControllableContent` and `jmix:droppableContent`.

### Access control (checkAccess)

- Reads `whitelist` from `McpConfigService`
- If empty → returns `null` (allow all)
- Extracts dot-path field paths from the query text via `extractFieldPaths(query, maxDepth)` — a hand-written recursive parser that handles aliases, inline fragments, `@` directives, and argument `()` blocks
- `maxDepth` is computed as the maximum segment count across all whitelist entries
- Paths whose any segment starts with `__` are removed (introspection always passes)
- For each path: allowed if any whitelist entry satisfies `pathCoveredBy(path, entry)` OR `pathIsContainerOf(path, entry)`
  - `pathCoveredBy`: entry is an ancestor of (or equal to) the path → entry covers this path
  - `pathIsContainerOf`: path is an ancestor of the entry → path must be traversed to reach the entry
- Blocked operations are logged at WARN with user name and client IP

### McpConfigService

OSGi `ManagedService` bound to PID `org.jahia.community.mcp`. Parses `whitelist` from a comma-separated string. Volatile field ensures visibility across threads without synchronization.

### React admin UI

- Mounted at Jahia admin route `Administration → MCP Server`
- Two Apollo queries on load: `GET_QUERY_FIELDS` and `GET_MUTATION_FIELDS` — deliberately split (one `__Type.fields` each) to avoid the bad-faith guard
- Lazy-loaded tree: clicking `▸` fires `GET_TYPE_FIELDS` (one per type name), result cached in `typeFields` state by type name
- Checkbox semantics: a node is `checked` if it or any ancestor is in the whitelist set (`isCoveredBySet`); checked-by-ancestor nodes render as disabled (opacity 0.55)
- Save merges the checkbox set into a string array and calls `mcpSaveSettings`

## Key implementation constraints

### SLF4J / Pax Logging

`Embed-Transitive=true` would otherwise embed `slf4j-api.jar` from the MCP SDK, causing `LoggerFactory` to return `NOPLogger` (no output). Fixed in `pom.xml`:

```xml
<Embed-Dependency>*;scope=compile|runtime;type=!pom;groupId=!org.slf4j</Embed-Dependency>
<Import-Package>org.slf4j;version="[1.7,3)",...</Import-Package>
```

This forces `org.slf4j` to be resolved from `pax-logging-api` at runtime.

### Bad-faith introspection guard

Any single GraphQL document must contain **at most one** `__Type.fields` selection. Queries with two or more are rejected by Jahia. Always split multi-type introspection into separate requests.

### Dot-path semantics

For a whitelist entry `E` and a query path `P`:
- `pathCoveredBy(P, E)` = `P.equals(E) || P.startsWith(E + ".")`
- `pathIsContainerOf(P, E)` = `E.startsWith(P + ".")`

A path passes the whitelist check if either condition is true for any entry.

### mcp:name → jcr:title

The skill display name is stored as `jcr:title` (from `mix:title` mixin), not as a custom `mcp:name` property. This avoids `ConstraintViolationException` and integrates with Jahia's standard editorial UI. In Java/GraphQL the field is exposed as `mcpName`.

## Build

```bash
mvn clean package          # builds Java + React (yarn build:production)
```

Frontend entry point: `src/javascript/index.js` → `McpConfig/McpConfig.jsx` registered as Jahia admin panel.

## Tests

```bash
cd tests
cp .env.example .env       # set JAHIA_IMAGE, JAHIA_LICENSE
yarn install
./ci.build.sh              # docker build of Cypress image (copies target/*.jar to artifacts/)
./ci.startup.sh            # docker-compose up + wait for Jahia + provision + run + collect
```

The `02-mcpEndpoint.cy.ts` and `03-mcpSkills.cy.ts` suites create a personal API token (scopes: `graphql`, `mcp`) at the start, delete all pre-existing tokens first for a clean state, and use `APIToken <value>` in `Authorization` headers for all `cy.request()` calls to `/modules/community-mcp`.

## Configuration files

| File | Purpose |
|---|---|
| `org.jahia.community.mcp.cfg` | Persists `whitelist` as comma-separated dot-paths |
| `org.jahia.bundles.api.authorization-mcp.yml` | Grants `mcp` permission to `admin` role |
| `org.jahia.modules.PersonalApiToken-mcp.cfg` | Binds API token scope `mcp` to URL `/modules/community-mcp` |
