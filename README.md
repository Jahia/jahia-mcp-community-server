# Jahia MCP Community Server

**GitHub:** https://github.com/Jahia/jahia-mcp-community-server

An extension of Jahia's [`mcp-server`](https://github.com/Jahia/mcp-server) module. It contributes
**administrator-only GraphQL tools** and **JCR-stored skills** to the
[Model Context Protocol (MCP)](https://modelcontextprotocol.io/) endpoint that `mcp-server` exposes.
The GraphQL tool can reach any Jahia GraphQL operation, not only the curated `site.*`, `page.*` and
`content.*` tools, but it is **read-only by default**: queries run, except the privileged `admin`
namespace, and mutations and subscriptions are refused until an administrator allows them in the whitelist.

> **2.0 is a breaking change.** Versions 1.x ran their own MCP endpoint at
> `/modules/community-mcp`. From 2.0 that endpoint is gone: the tools are served by `mcp-server` at
> `/modules/mcp`, under new names. See [Migrating from 1.x](#migrating-from-1x) and
> [CHANGELOG.md](CHANGELOG.md).

## Contents

- [How it works](#how-it-works)
- [Requirements](#requirements)
- [Installation](#installation)
- [Authentication and access](#authentication-and-access)
- [MCP client setup](#mcp-client-setup-claude-code)
- [Tools](#tools)
- [Skills](#skills)
- [Access control: the whitelist](#access-control-the-whitelist)
- [Administration UI](#administration-ui)
- [Security notes](#security-notes)
- [Troubleshooting](#troubleshooting)
- [Migrating from 1.x](#migrating-from-1x)
- [Testing](#testing)

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
documents are dispatched **in-process** to `graphql-dxm-provider`, under the identity of the
administrator who called the tool, so JCR ACLs apply normally.

## Requirements

| Component | Version | Notes |
|---|---|---|
| Jahia | 8.2+ | Built against the Jahia 8.2.2.1 parent POM |
| `graphql-dxm-provider` | required at runtime | Declared in `jahia-depends`; the GraphQL servlet is its `OsgiGraphQLHttpServlet` |
| `mcp-server` | **0.5.x** (`[0.5,0.6)`) | Provides the tool SPI. The SPI is pre-1.0, so the import range is kept tight |
| Declarative Services | 1.4 (Felix SCR 2.1 or later) | Required for the configuration-driven components |
| `personal-api-tokens` | optional | Only needed for API-token authentication |
| Java | 17 | |

`mcp-server` must be deployed first. The 2.0.0 build targets `mcp-server` 0.5.0-SNAPSHOT; a release
of this module must be built against a released `mcp-server` 0.5.x.

## Installation

Deploy `mcp-server` first, then this bundle (Module Manager, or drop the JAR into `$JAHIA_HOME/modules/`).

The bundle ships `org.jahia.community.mcp.cfg` (the whitelist, empty by default). Keep that
configuration in place: without it the whitelist-gated tool and the settings API stay unavailable
(see [Troubleshooting](#troubleshooting)).

On every activation the module seeds its default skills into JCR under
`/sites/systemsite/contents/mcp-skills/`, creating each one only when it is absent.

## Authentication and access

Authentication is handled by `mcp-server`: send a personal API token (or HTTP Basic credentials) to
`/modules/mcp`.

```
Authorization: APIToken <your-token>
```

`mcp-server` admits authenticated, non-guest users. The **community tools add their own gate**: the
caller must hold the `admin` permission on the repository root (a server administrator). Other users
still see the community tools in `tools/list`, but every call returns a `forbidden` error. The
built-in `mcp-server` tools are unaffected.

The gate uses the caller that `mcp-server` binds to the request thread: the same user and realm. The
`Authorization` header is never forwarded to the GraphQL servlet, so a GraphQL call runs as exactly
that user.

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

| Tool | Safety | Tags | Purpose |
|---|---|---|---|
| `community.graphql.execute` | destructive | `destructive`, `reads-ugc`, `community` | Run a GraphQL query or mutation, subject to the whitelist |
| `community.graphql.introspect` | read-only | `community` | Discover the GraphQL schema: type list and root types, or specific types (bounded) |
| `community.skill.list` | read-only | `community`, `reads-ugc` | List skills: `name`, `mcpName`, `description` |
| `community.skill.get` | read-only | `community`, `reads-ugc` | Read one skill's full Markdown content |

`reads-ugc` marks tools whose output is editor-authored content, which may contain instructions.
Treat it as data.

Results follow `mcp-server`'s contract: a JSON text block on success, and
`{"error":{"code","message","hint"}}` with `isError: true` on failure. Codes used by the community
tools: `forbidden`, `validation`, `not_found` and `internal`.

### `community.graphql.execute`

| Field | Type | Required | Description |
|---|---|---|---|
| `query` | string | yes | GraphQL query or mutation document |
| `variables` | object | no | Variables map for the operation |

- On success the result is the GraphQL response JSON (`data` / `errors`). GraphQL-level errors arrive
  inside that payload with HTTP 200, so clients must check `errors` even when the call succeeds.
- A document refused by the whitelist returns `forbidden` with the reason (see
  [Access control](#access-control-the-whitelist)).
- An HTTP-level failure of the GraphQL servlet is reported as an error: 400 becomes `validation`,
  401 and 403 become `forbidden`, anything else becomes `internal`.
- A response larger than **5 MB** is refused as an error. Narrow the query and retry.
- The tool is announced as **destructive** because it can run any allowed mutation, so well-behaved
  clients ask for confirmation before calling it.

### `community.graphql.introspect`

| Field | Type | Required | Description |
|---|---|---|---|
| `typeNames` | string[] | no | 1–25 GraphQL type names to describe in full |

- **No arguments:** returns `schema` (root type names and every type's `name` + `kind`) and the full
  details of the root `Query` and `Mutation` types.
- **`typeNames`:** returns the full details (fields, arguments, input fields, enum values) of those
  types only.

Every answer also carries `skippedTypes` (unknown, or the request failed) and `truncatedTypes`
(not fetched because the result reached its **100,000-character** budget; ask for them in a further
call). Each type is fetched in its own request, so Jahia's bad-faith introspection guard (which rejects
documents with more than one `__Type.fields` selection) never trips. Type names are passed as GraphQL
variables, never formatted into the document.

The schema lists every operation Jahia offers, including ones the whitelist refuses. A listed operation
is not necessarily allowed in `community.graphql.execute`.

### `community.skill.list` / `community.skill.get`

`community.skill.list` returns `{"skills":[{"name","mcpName","description"}]}`. Each `name` is the
path relative to the skills root (for example `default/hello-jahia`). Pass it unchanged to
`community.skill.get`, which returns `{"name","mcpName","description","content"}`. An unknown name
returns `not_found`.

## Skills

Skills are Markdown documents stored as `mcp:skill` JCR nodes under
`/sites/systemsite/contents/mcp-skills/` (a `jnt:contentList`, with `jnt:contentList` sub-folders). They
deliver Jahia-specific knowledge and instructions to any MCP client session. Skills are hidden from the
editors' content-creation pickers (`jmix:hiddenType`), and editing is restricted by the container's ACL
(see [Security notes](#security-notes)).

A skill's **key** is its path relative to the skills root (`default/hello-jahia`). The same key is
returned by `skills { name }`, accepted by `saveSkill`, `deleteSkill` and `community.skill.get`.

### Default skills

Default skills are `.md` files under `src/main/resources/skills/` and are seeded into JCR on module
activation, **only when the node does not already exist**. Consequences:

- Editing a bundled file does not update an existing installation. To refresh a default skill, update
  it with `saveSkill` (GraphQL), or delete its node under `/sites/systemsite/contents/mcp-skills/` and
  restart the module.
- A default skill that an administrator deletes is seeded again the next time the module activates.

The bundle ships these skills:

| Key | Purpose |
|---|---|
| `default/hello-jahia` | Smoke-test skill: answers "Hello Jahian" to "Hello Jahia". It is used by the integration tests and can be deleted |
| `default/jahia-properties` | Quick guide to configuring Jahia 8.2 through `jahia.properties`, node properties and OSGi `.cfg` files |
| `default/jahia-properties-ref` | Reference of all Jahia 8.2 properties, grouped by category |

### Managing skills and settings via GraphQL

The module's GraphQL operations are grouped under one `communityMcp` namespace on `Query` and
`Mutation` (types `CommunityMcpQuery` and `CommunityMcpMutation`). Every operation requires the
`admin` permission and is an administrator-only entry point.

```graphql
# List all skills (name = key, relative to the skills root)
query {
    communityMcp {
        skills { name mcpName description content }
    }
}

# Create or update a skill. Intermediate folders are created for nested keys.
mutation {
    communityMcp {
        saveSkill(
            name: "team/my-skill",
            mcpName: "My Skill Display Name",
            description: "What this skill does",
            content: "# My Skill\n\nInstructions for Claude..."
        )
    }
}

# Delete a skill by key
mutation {
    communityMcp {
        deleteSkill(name: "team/my-skill")
    }
}

# Read / write the whitelist
query { communityMcp { settings { whitelist } } }
mutation { communityMcp { saveSettings(whitelist: ["query:currentUser", "query:jcr"]) } }
```

Rules for `saveSkill` and `deleteSkill`:

- `name` must match `[A-Za-z0-9_-]` segments separated by single `/`. Leading or trailing slashes,
  empty segments and `.` or `..` are refused.
- Missing intermediate folders are created. A segment that is already a skill cannot become a folder.
- An existing node at the key must be an `mcp:skill` node. Anything else is refused.
- `content` holds at most 200,000 characters.
- Both return `true` on success. A refused call fails with a GraphQL error (in the `errors` array, with a
  `null` result) whose message gives the reason: `Invalid skill name: …`, `The skill content is too long: …`,
  `The skill could not be saved: …` (a folder or another node uses the key, or the repository failed) or
  `The skill was not deleted: …` (no skill with that key). The key is never echoed back; details are in
  the server log.

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
sub-folder structure under `mcp-skills/`. The bundled file is seeded only on a fresh install (see
[Default skills](#default-skills)).

## Access control: the whitelist

The whitelist controls which GraphQL operations `community.graphql.execute` may run. The other
community tools are not affected by it.

### Read-only default

| Whitelist | Effect |
|---|---|
| **Empty (default)** | Every **query** is allowed, except the queries under `admin`. Every **mutation** and **subscription** is refused |
| Non-empty | Only the listed operations, and the fields below them, are allowed. Mutations and queries are allowed only when listed |

An empty list is therefore not "allow everything". Saving an empty list (for example with **Clear the
allow list** in the UI, or `saveSettings(whitelist: [])`) restores this read-only default.

**The `admin` query namespace needs an explicit entry.** Queries under `admin` expose server state,
including the raw values of every OSGi configuration (`admin.jahia.configuration(pid: …)`), which can hold
passwords and keys. The read-only default refuses them, with a reason that names `query:admin`. To allow
them, add an explicit entry such as `query:admin.jahia.isAlive`, or `query:admin` for the whole namespace.

"Read-only" protects against writes, not against disclosure. Every other query still returns whatever the
calling administrator can read, and the result goes to the AI client and its model provider. Keep the
`admin` entries as narrow as the task allows.

**The first entry ends the read-only default.** As soon as the list holds one entry, only the listed
operations are allowed, on both sides. Adding a single `mutation:` entry to an empty list therefore
refuses every query until you add `query:` entries for the queries you need, for example `query:jcr` and
`query:currentUser`.

**Upgrades from 1.x:** an installation keeps its existing `org.jahia.community.mcp.cfg` (the shipped file
is a default configuration and is never overwritten). That file may still carry the 1.x comment
"If empty, all operations are allowed". The comment is stale. An empty whitelist is the read-only default
described above, and it no longer allows mutations. Unprefixed 1.x entries are read as query-only, so
review the list after the upgrade (see [Migrating from 1.x](#migrating-from-1x)).

A non-empty list that contains only malformed entries denies every operation. Malformed entries are
refused by `saveSettings`, so this state can only come from outside `saveSettings`: the `.cfg` file,
provisioning, the Karaf `config:edit` command, or Jahia's generic OSGi configuration API (for example the
`admin.jahia.configuration` GraphQL mutation called directly on `/modules/graphql`).

### Entry syntax

Each entry names an **operation type** and a dot-separated field path that mirrors the GraphQL
selection hierarchy:

| Entry | Covers |
|---|---|
| `query:admin` | Every query under `admin` |
| `query:admin.jahia.isAlive` | Only that query field |
| `mutation:jcr` | Every mutation under `jcr` |
| `mutation:jcr.deleteNode` | Only that mutation |

- Allowing a query never allows the mutation of the same name: `query:jcr` does **not** permit
  `mutation { jcr { … } }`.
- A document is allowed only if **every** operation it contains is allowed, so a mutation cannot hide
  behind an allowed query in the same document.
- A prefix entry grants everything below it. `mutation:jcr` allows every mutation under `jcr`. Prefer
  exact paths for mutations.
- A deeper mutation entry also runs the intermediate mutation fields above it. GraphQL cannot run
  `mutation { jcr { addNode { mutateProperty } } }` without running `addNode`, so the entry
  `mutation:jcr.addNode.mutateProperty` also lets `addNode` create a node. Intermediate fields are not
  checked on their own. Choose entries whose intermediate fields may run, and prefer the leaf
  operations you need.
- Other operation types (for example `subscription:`) and malformed paths grant nothing.
- `mutation:communityMcp` and `mutation:admin.jahia.configuration` (each with everything under it) are
  **always refused**, whatever the whitelist says, including under a broader entry such as `mutation:admin`.
  The data plane cannot rewrite its own guard: the second path is the generic OSGi configuration mutation,
  which could rewrite this module's configuration. `saveSettings` refuses entries for these paths too.

### Legacy entries

An entry without a prefix (`jcr`, as written by 1.x) is accepted and treated as **query-only**. A
`WARN` lists such entries when the configuration loads, and the admin UI shows them as `query:`
entries, ready to save in the new form. Malformed entries are dropped from the enforced set, and each
one is logged at `WARN`.

### Where the whitelist is stored

The entries are the `whitelist` key of the OSGi configuration `org.jahia.community.mcp`:

```properties
whitelist=query:jcr,query:currentUser,mutation:jcr.deleteNode
```

`saveSettings` validates the list before storing it. A malformed or refused entry makes the call fail
with a GraphQL error that names the entry, and nothing is stored.

**Clusters.** `saveSettings` writes the OSGi configuration on the node that served the request only. In a
cluster, set the whitelist on every node: through the administration UI or `saveSettings` on each node, or
through provisioning or the `.cfg` file. Until then the other nodes keep their previous list.

### Rules applied to each document

- A document that cannot be parsed is refused, in every state of the whitelist.
- A named fragment spread (`...Name`) is refused in **every** state, the read-only default included,
  because the gate does not expand it and so cannot check what it selects. Inline fragments
  (`... on Type { … }`) are supported.
- Introspection fields (`__schema`, `__type`, `__typename`) always pass.

### Logging

Every refused operation is logged at `WARN`. A policy denial shows the operation path, the reason,
the user and the client IP. Its shape is:

```
WARN ... MCP operation blocked: path='mutation:jcr.deleteNode', reason='...', user='john', ip='10.0.0.5'
```

A document that cannot be parsed, or that contains a named fragment spread, is logged with the user and
IP and the reason, but without a path.

## Administration UI

**Administration → MCP Server** edits the whitelist. The page writes the same `whitelist` entries
through `communityMcp { saveSettings }`.

- **Two groups.** Queries and mutations are listed separately, each with bulk actions: allow all,
  clear. The operation tree is loaded lazily, one type at a time.
- **Keyboard.** The tree follows the WAI-ARIA tree pattern: Up and Down move between items, Home and
  End go to the first and last item, Right expands or enters the children, Left collapses or moves to
  the parent, Space or Enter checks or unchecks an item.
- **Confirmations.** Any edit that empties a non-empty list asks for confirmation first, because an
  empty list restores the read-only default, which allows every query outside `admin`: Clear, Clear queries or Clear
  mutations, unchecking the last checked item, and removing the last entry. Clearing queries while
  mutations remain, and allowing all mutations, are confirmed too. Discard changes is never confirmed:
  it restores the saved list.
- **Locked operations.** The never-allowed paths, `mutation:communityMcp` and `mutation:admin.jahia.configuration`,
  are shown but cannot be checked.
- **Entries that need your attention.** Entries whose root operation is missing from the current
  schema, entries that are locked, and entries the server ignores (another operation type such as
  `subscription:`, or a malformed entry) are kept in the list and shown there as such, each with a
  Remove action. They are never dropped silently. When the list only holds ignored entries, the policy
  banner says that every operation is refused.
- **Save.** Save is refused, with a message naming the entries, while invalid or locked entries remain:
  the server would reject them. Remove them first.
- **Filter.** Matches the names and paths of all root operations and of fields already loaded.
- **Unsaved changes.** The page shows an indicator and warns when you reload or close the page with
  unsaved changes. Moving to another administration page does not warn, so save first. **Discard changes**
  restores the saved list. The save bar sits at the end of the panel and is not sticky, so it never
  covers the focused control.
- **Focus.** Focus never gets lost. After a confirmed edit whose control disappears, it goes to the
  allow-list heading; after Discard changes or Dismiss, to Save; after Remove, to the next entry of the
  same list (the previous one at the end), else to the list summary or the allow-list heading.
- **Messages.** Status messages never time out. A success or info message clears on the next edit; an
  error stays until it is dismissed or the save is retried. A failed save shows the server's reason.
- The UI targets WCAG 2.2 Level AAA.

## Security notes

- **Read-only by default.** With an empty whitelist, queries are allowed to administrators, except the
  queries under `admin`, and mutations and subscriptions are refused. Allowing a mutation requires an
  explicit `mutation:` entry, and allowing an `admin` query requires an explicit `query:admin…` entry.
  Read-only does not mean non-sensitive: any allowed query returns what the administrator can read, and
  that result is sent to the AI client. See [Read-only default](#read-only-default).
- **Administrator gate.** Every community tool and every `communityMcp` operation checks the `admin`
  permission on the repository root with the caller's own session. A failure to determine the caller
  denies the call.
- **Caller identity.** The GraphQL call runs as the bound caller. No `Authorization` header is forwarded
  to the in-process servlet.
- **Accepted risk, SEC-4.** The API scopes of `graphql-dxm-provider` (security-filter) are not applied to
  in-process calls, because no scope context exists on MCP worker threads. JCR ACLs and the administrator
  gate are the boundary. Do not broaden the gate without revisiting this.
- **Accepted risk, SEC-6.** A prefix entry grants everything below it, such as `mutation:jcr`. Use exact
  paths unless a whole namespace is intended.
- **Accepted risk, SEC-6b.** Self-protection is a deny list of two paths (`mutation:communityMcp` and
  `mutation:admin.jahia.configuration`). Any other mutation that changes server state, such as a module,
  bundle or provisioning mutation contributed by another module, including one installed later, is
  protected only by your choice of entries. Do not whitelist `mutation:admin` or other broad mutation
  roots unless you mean it.
- **Skills and generic JCR mutations.** The never-allowed paths block `communityMcp.saveSkill`, but a
  `mutation:jcr` entry reaches the skill nodes under `/sites/systemsite/contents/mcp-skills` through the
  generic JCR mutations. The container ACL stops ordinary users, not `root`, which bypasses ACLs. With
  `mutation:jcr` whitelisted and a `root` token, a prompt-injected session could rewrite skills. Use a
  token of a non-root administrator and exact `mutation:jcr.…` entries.
- **Skills use a system session.** Skill reads and writes run as the system session, so they bypass
  per-node ACLs and the whitelist. This is deliberate: skills are shared instruction documents that every
  MCP client may read, and the only write paths are the administrator-gated `saveSkill` and `deleteSkill`
  operations and the module's own seeding. The skills container has its ACL inheritance broken, so no
  ordinary role can write skills through generic JCR paths. This also applies to a container created by a 1.x
  install: on the next activation its inheritance is broken and the local role grants (ACL entries) on the
  container and on every folder and skill below it are removed. Skills that already exist are kept as they
  are, so review the skills of an upgraded 1.x install (see [Migrating from 1.x](#migrating-from-1x)). The skill type carries `jmix:hiddenType`, so
  skills are hidden from the editors' content-creation pickers. Revisit this if the gate is broadened beyond
  administrators or deny ACLs are added to skill nodes.
- **Named fragment spreads** and **unparseable documents** are refused in every state, including the
  read-only default. Inline fragments are supported.
- **Never-allowed paths.** `mutation:communityMcp` and `mutation:admin.jahia.configuration` are refused whatever
  the whitelist says. Intermediate mutation fields of a deeper entry do run (see
  [Entry syntax](#entry-syntax)), so keep mutation entries as narrow as the task allows.
- **Cluster.** The whitelist is stored per node; set it on every node (see [Where the whitelist is stored](#where-the-whitelist-is-stored)).
- **Response size and cost.** In-process GraphQL responses are capped at 5 MB. The cap applies after the
  servlet has run the query and serialized its result in memory, and there is no timeout or complexity
  limit. An expensive query (for example a JCR query over the whole repository) runs to completion on the
  `mcp-server` worker thread. The tool is administrator-only; this is accepted.
- **Introspection** is bounded to 25 types per call and 100,000 characters of output.
- **Client IP** in audit lines is the TCP peer (`getRemoteAddr()`). `X-Forwarded-For` is ignored.
  Configure Tomcat's `RemoteIpValve` behind a trusted proxy.
- **Request size and transport** limits are those of `mcp-server`, which reads the request body without a
  size cap in 0.5.
- **Audit.** Every call records an audit event `tool.execute.ok` or `tool.execute.error` with `tool`, `user`
  and, on error, `code`. `community.graphql.execute` adds `queryHash` (SHA-256 of the document). The
  document text and variables are never logged.

## Troubleshooting

| Symptom or message | Cause | Fix |
|---|---|---|
| `The community tools are restricted to server administrators.` (`forbidden`) | The caller does not hold `admin` on the repository root | Use an administrator's token, or use the built-in `mcp-server` tools |
| `Operation not allowed: mutations require an explicit mutation:<path> whitelist entry …` | Read-only default: the whitelist is empty and the call is a mutation | Add the mutation as `mutation:<path>` in Administration → MCP Server, or use a query |
| `Operation not allowed: queries under 'admin' require an explicit query:admin<.path> whitelist entry …; '<path>' is not allowed` | Read-only default: the whitelist is empty, and the `admin` query namespace is not part of it | Add an explicit entry, for example `query:admin.jahia.isAlive`, or `query:admin` for the whole namespace |
| `Operation not allowed: '<path>' is not in the whitelist` | The operation is not covered by an entry | Add an exact `query:<path>` or `mutation:<path>` entry |
| Every query refused after adding one `mutation:` entry | The list is no longer empty, so the read-only default no longer applies: only listed operations run | Add `query:` entries for the queries you need |
| A denial saying the operation can never be run through `community.graphql.execute` | The operation is a never-allowed path: `mutation:communityMcp` or `mutation:admin.jahia.configuration`, or anything under them | Not allowed; by design. Change the settings in the UI, not through the MCP tool |
| `Operation not allowed: the GraphQL document could not be parsed` | Syntax error in the document | Fix the document; check names with `community.graphql.introspect` |
| A denial that mentions a named fragment | The document contains a `...Name` spread. Refused in every state | Inline the fields, or use `... on Type { … }` |
| `Operation not allowed: subscriptions are not supported` | A subscription was sent while the whitelist is empty (read-only default) | Subscriptions are not available through this tool. With a whitelist, the message is `'subscription:<path>' is not in the whitelist` |
| `Malformed whitelist entry '…': expected query:<path> or mutation:<path>` | `saveSettings` rejected an entry | Correct the entry; `<path>` is dot-separated GraphQL names |
| `Whitelist entry '…' is refused: …` | The entry names a never-allowed path (`mutation:communityMcp` or `mutation:admin.jahia.configuration`, or anything under them) | Not allowed; by design |
| `The GraphQL schema could not be introspected.` (`internal`) | The GraphQL servlet is not available | Check that `graphql-dxm-provider` is started, then the Jahia log |
| `The GraphQL operation could not be executed.` (`internal`) | The servlet failed, or the response exceeded 5 MB | Check the Jahia log; narrow the query if the response was too large |
| `The GraphQL response exceeds 5242880 bytes` (in the log) | Response over the 5 MB cap | Request fewer fields, or paginate |
| `The MCP configuration service is not available: check that the org.jahia.community.mcp configuration exists` | The `org.jahia.community.mcp` configuration is missing. The gated tool and the settings API are disabled, and the UI cannot save | Restore `org.jahia.community.mcp.cfg` in `karaf/etc` (or re-deploy the bundle). The UI works again once the configuration exists |
| `community.graphql.execute` missing from `tools/list` | The whitelist configuration is missing, or the GraphQL servlet is not available | Restore the configuration; check that `graphql-dxm-provider` is started |
| `Community MCP caller identity is not bound … denying` (WARN) | The caller is not bound on the thread, or the bound user differs from the request user. Usually a version mismatch with `mcp-server` | Use `mcp-server` 0.5.x |
| `Invalid skill name: …` (GraphQL error of `saveSkill` or `deleteSkill`) | The key breaks the name rules | Use `[A-Za-z0-9_-]` segments separated by single `/` |
| `The skill content is too long: …` | `content` exceeds 200,000 characters | Split the skill |
| `The skill could not be saved: …` | A segment is an existing skill, the key holds a folder or another non-skill node, or the repository failed | Choose another key; check the server log |
| `The skill was not deleted: …` | No skill has that key, or the key names a folder | Use the `name` returned by `skills` or `community.skill.list` |
| `Skill '…' not found.` (`not_found`) | Wrong key | Use the `name` returned by `community.skill.list` |
| Edit to a bundled `.md` file has no effect | Default skills are seeded only when absent | Update with `saveSkill`, or delete the node and restart the module |
| A deleted default skill came back | Default skills are re-seeded on module activation | Delete it again after the restart, or remove the bundled file |
| WARN: `MCP whitelist entries without an operation prefix are treated as query-only` | Legacy entries | Rewrite them as `query:<path>` and add `mutation:<path>` where needed |
| Mutations refused after upgrading from 1.x, although the `.cfg` comment says an empty list allows all | The 1.x comment in the kept `.cfg` is stale. An empty list is the read-only default | Add the mutation entries you need, see [Migrating from 1.x](#migrating-from-1x) |
| Whitelist change works on one node but not another | `saveSettings` writes only the node that served the request | Set the whitelist on every node, see [Where the whitelist is stored](#where-the-whitelist-is-stored) |
| WARN: `Ignoring malformed MCP whitelist entry` | Malformed entry in the `.cfg` file | Correct or remove the entry |
| UI: `Entries that need your attention` | The entry's root operation is not in the schema, it is locked, or the server ignores it (malformed, or another operation type such as `subscription:`) | Remove it, unless it is expected to come back. Locked and ignored entries block Save |
| UI: `The settings were not saved. The server said: …` | The server rejected the list | Read the reason and correct the entry |
| UI: browser warning when reloading or closing the page | The list changed and was not saved | Save, or use **Discard changes**. Moving to another administration page does not warn |

## Migrating from 1.x

| 1.x | 2.0 |
|---|---|
| Endpoint `/modules/community-mcp` | `/modules/mcp` (served by `mcp-server`) |
| `executeGraphQL` | `community.graphql.execute` |
| `introspectSchema` | `community.graphql.introspect` (bounded: type list and root types, or `typeNames`) |
| `listSkills` | `community.skill.list` (returns `{"skills":[…]}`; names are relative paths) |
| `getSkill` | `community.skill.get` (returns JSON with a `content` field) |
| Token scopes `graphql` and `community-mcp` | Any token accepted by `mcp-server`. The caller must be a server administrator |
| `GET /modules/community-mcp` health check | Removed. Use MCP `tools/list` |
| GraphQL namespace `mcp { … }` | `communityMcp { … }` (types `CommunityMcpQuery`, `CommunityMcpMutation`, …) |
| Errors as `{"errors":[{"message"}]}` text | `{"error":{"code","message","hint"}}` |
| Whitelist entry `jcr`, allowing queries and mutations | `jcr` is query-only. Write `query:jcr` and `mutation:jcr` |
| Empty whitelist allowed everything | Empty whitelist is read-only: queries only, and not the queries under `admin` |
| Whitelist entries as dot-paths | `query:<path>` / `mutation:<path>` entries |
| Skill list names were node names | Skill key is the relative path (for example `default/hello-jahia`) |

Update your MCP client URL and any tool names hard-coded in prompts or skills. Review your whitelist:
an entry that was meant to allow mutations needs an explicit `mutation:` entry, and an empty whitelist
no longer allows mutations.

An upgraded installation keeps its existing `org.jahia.community.mcp.cfg` in `karaf/etc`. Its comments
are stale: the "If empty, all operations are allowed" line from 1.x no longer applies. Update the
`whitelist` key (or the UI) rather than relying on the comments. In a cluster, do this on every node.
Named fragment spreads are refused in every state, and `mutation:admin.jahia.configuration` is always
refused. Update any client that sends named fragment spreads to use inline fragments. With an empty
whitelist, queries under `admin` need an explicit `query:admin…` entry.

**Review the skills.** In 1.x, editors could be granted roles on the skills container, its folders or its
skill nodes, and could write skills that every administrator's AI client reads. On activation, 2.0 breaks
the container's ACL inheritance and removes the local role grants on the container and everything below
it. It does not change the content of existing skills. After the upgrade, read every skill under
`/sites/systemsite/contents/mcp-skills` (or list them with `community.skill.list`) and delete or correct any
skill you did not write. If a role grant there was intended, it has to be replaced: from 2.0 skills are
written only through the administrator-gated `saveSkill` and `deleteSkill` operations.

## Testing

```bash
mvn clean install              # Java unit tests + Jest + bundle
```

Docker-based Cypress integration tests live in `tests/`. They provision `mcp-server` 0.5.0-SNAPSHOT.

```bash
cd tests
cp .env.example .env          # fill JAHIA_IMAGE and JAHIA_LICENSE
yarn install
./ci.build.sh                  # build the Cypress Docker image
./ci.startup.sh                # start Jahia, run the tests and collect results
```

| Spec | What is tested |
|---|---|
| `01-mcpSettings.cy.ts` | Settings GraphQL API: read, write, round-trip, prefixed entries, rejection of malformed and never-allowed entries (`mutation:communityMcp`, `mutation:admin.jahia.configuration`) |
| `02-mcpEndpoint.cy.ts` | `community.graphql.execute`: whitelist enforcement, path coverage, queries vs mutations, `admin` queries refused by the read-only default and allowed by `query:admin`, introspection pass-through, 401 when unauthenticated |
| `03-mcpSkills.cy.ts` | Skills GraphQL API, `community.skill.list` → `community.skill.get` round-trip, default skill seeding |
| `04-mcpToolCatalogAndGate.cy.ts` | Tools listed in the `mcp-server` catalog with safety annotations; non-administrators refused (HTTP Basic, user created with `createUser`) |
| `05-mcpToolGating.cy.ts` | Gating per tool: a narrow whitelist does not affect introspection or skills; bounded introspection (default, `typeNames`, cap) |
| `06-mcpWhitelistEnforcement.cy.ts` | Fail-closed guards: named fragment spreads (refused under the read-only default too, and the hidden mutation does not run), `mutation:admin.jahia.configuration` refused even with `mutation:admin` whitelisted, multi-operation documents, unparseable documents |
| `07-mcpCallerIdentity.cy.ts` | GraphQL runs as the calling user (`currentUser` returns the caller) |
| `08-mcpAdminUi.cy.ts` | Administration UI smoke test only: loads at the admin route, shows the Queries and Mutations groups, persists a checked entry when saved, shows `mutation:admin.jahia.configuration` locked and saves nothing when it is clicked. Keyboard, confirmations, problem entries and error display are covered by Jest only |
