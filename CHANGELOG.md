# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [2.0.0] - Unreleased

2.0.0 moves the module from its own MCP endpoint to a set of tools inside Jahia's `mcp-server`. It is
a breaking release. See [Migrating from 1.x](README.md#migrating-from-1x).

### Breaking changes

- **Endpoint.** `/modules/community-mcp` is removed. The tools are served by `mcp-server` at `/modules/mcp`.
- **Tool names.** `executeGraphQL` → `community.graphql.execute`; `introspectSchema` →
  `community.graphql.introspect`; `listSkills` → `community.skill.list`; `getSkill` → `community.skill.get`.
- **Error envelope.** Errors are `{"error":{"code","message","hint"}}` with `isError: true`, using the codes
  `forbidden`, `validation`, `not_found`, `unavailable` and `internal`. The 1.x text format is gone.
- **Authentication.** The token scopes `graphql` and `community-mcp` are gone. Any token accepted by
  `mcp-server` works, and the caller must hold `admin` on the repository root. The community tools need an
  explicit `Authorization` header (API token or HTTP Basic): a call authenticated only by a session cookie
  is refused.
- **Read-only default.** An empty whitelist no longer allows every operation. It allows every query except
  the queries under the privileged `admin` namespace, and refuses every mutation and subscription. Queries
  under `admin` need an explicit `query:admin…` entry. As soon as the list holds one entry, only the listed
  operations run: adding one `mutation:` entry refuses every query that is not listed. The read-only default
  is not non-sensitive (see Security); production installations should set an explicit allow list.
- **Whitelist syntax.** Entries are `query:<path>` or `mutation:<path>`. An unprefixed entry is accepted as
  a legacy **query-only** entry and logged at WARN. Malformed entries grant nothing.
- **Whitelist configuration.** `org.jahia.community.mcp` is a required (`REQUIRE`) configuration. Without it
  the gated tool and the settings API are unavailable (fail closed) instead of defaulting to a permissive state.
- **GraphQL namespace.** `mcp { … }` → `communityMcp { … }`. Types are `CommunityMcpQuery`,
  `CommunityMcpMutation`, `CommunityMcpSettings` and `CommunityMcpSkill`.
- **Skill key.** `skills { name }` and `community.skill.list` return the path relative to the skills root
  (for example `default/hello-jahia`), the same key that `saveSkill`, `deleteSkill` and
  `community.skill.get` accept. Before, the name was the bare node name.
- **Skill mutations fail with GraphQL errors.** `saveSkill` and `deleteSkill` no longer return `false` on a
  refusal (invalid key, unknown skill, a key that holds a folder or another node, content over 200,000
  characters). They fail with a GraphQL error whose message gives the reason, and return `true` on success.
- **Removed.** The `GET /modules/community-mcp` health check, the 2 MB request cap of the 1.x endpoint, and
  the `community-mcp` API scope.
- **Dependencies.** Requires `mcp-server` 0.5.x (`[0.5,0.6)`), `graphql-dxm-provider` 3.9.0 or later (below
  4.0) and Declarative Services 1.4 (Felix SCR 2.1 or later).
- **Exports.** Only `org.jahia.community.mcp.graphql` is exported. The initial-content import
  (`src/main/import`) is removed.

### Security

- **Administrator gate on every tool.** The check uses the caller bound by `mcp-server` (same username and
  realm) and the JCR `admin` permission on `/`. It fails closed on any missing binding, mismatch or
  repository error. All tools run through one template method, so none can skip the gate or the audit event.
- **Explicit `Authorization` header.** Community tool calls authenticated only by a session cookie are
  refused, so a cross-site form post sent by a logged-in administrator's browser cannot drive them. Whether
  the built-in `mcp-server` tools accept such a call is decided by `mcp-server`.
- **No credential forwarding.** The in-process GraphQL call carries no `Authorization` header. It runs as the
  bound caller, with JCR ACLs applied.
- **Refused by design.** `mutation:communityMcp` and `mutation:admin.jahia.configuration` (each with everything
  under it) are always refused by the gate, and `saveSettings` refuses entries for them. The data plane cannot
  rewrite its own guard, or this module's configuration through the generic OSGi configuration mutation.
- **Privilege-granting mutations are always refused.** Role grants and revocations (`grantRoles`,
  `revokeRoles`, at any depth) and personal API token management (`admin.personalApiTokens`) can never be
  run through `community.graphql.execute`, whatever the whitelist says, so a broad entry such as
  `mutation:jcr` cannot be used to make a user an administrator or to mint a token.
- **`admin` queries need an explicit entry.** The read-only default refuses the queries under `admin`,
  which expose server state such as the raw values of every OSGi configuration, secrets included. The
  denial names `query:admin`.
- **Read-only is not non-sensitive.** The read-only default means no writes. Every other query still returns
  what the calling administrator can read, such as settings stored in JCR (the mail server configuration, for
  example) and user data, and the result is sent to the AI client. Use an explicit allow list in production.
- **Unparseable documents** are refused in every state, as in 1.0.4. The message now gives the line and column
  of the syntax error. **Named fragment spreads**, refused in 1.0.4 only when a whitelist was set, are now
  refused in every state, including the read-only default. Inline fragments are supported.
- **Whole-document check.** Every operation in a document is checked, so a mutation cannot hide behind an
  allowed query.
- **Intermediate mutation fields run.** A deeper mutation entry (for example `mutation:jcr.addNode.mutateProperty`)
  also runs the mutation fields above it, because GraphQL cannot run the deeper field without them. Choose entries
  accordingly and prefer leaf paths.
- **Bounded introspection.** At most 25 types per call and 100,000 characters of output. Type names are passed as
  GraphQL variables, never formatted into the document.
- **Bounded responses.** In-process GraphQL responses are capped at 5 MB. A larger response is refused with
  `validation` and a hint to narrow the query.
- **Unreadable configuration.** A configuration update that cannot be read keeps the whitelist in force and
  logs an ERROR; one that cannot be read at startup refuses every operation until it is fixed. Neither falls
  back to the read-only default.
- **Skill container.** The skills container has its ACL inheritance broken. Skills are written only through the
  administrator-gated operations and the module's own seeding, which run as the system session. Skill node
  types are checked before reads, updates and deletes.
- **1.x skills hardening.** On activation the module also removes the local role grants (ACL entries) on the
  skills container and on every folder and skill below it, so grants made in 1.x no longer let editors write
  skills. Existing skill content is not changed: review it (see Upgrade notes).
- **Audit.** Each call records `tool.execute.ok` or `tool.execute.error`. `community.graphql.execute` records a
  SHA-256 `queryHash`, never the document text or the variables.
- **Accepted risks** (documented, not fixed): API scopes are not applied to in-process calls; a prefix
  entry grants everything below it; self-protection is a deny list, so other
  state-changing mutations are protected only by the entries you choose; a `root` caller with
  `mutation:jcr` whitelisted can rewrite skills through the generic JCR mutations; the 5 MB response cap
  applies after the query has run, and there is no timeout.

### Added

- **Whitelist state.** Snapshot-based, immutable and published atomically on configuration change. An empty
  whitelist is a documented read-only default.
- **Tool descriptions.** Each tool describes its safety, its refusals and where to look next (`community.graphql.introspect`
  first, the built-in tools when they fit). Skill text is tagged `reads-ugc`.
- **Introspection.** `community.graphql.introspect` returns `skippedTypes` and `truncatedTypes` so callers can
  request the rest.
- **Skill management.** `saveSkill` creates intermediate folders for nested keys.
- **Cluster flag.** `communityMcp { settings { clustered } }` reports whether Jahia runs as a cluster; the
  administration UI then warns that saving affects this node only, and says so again after a save.
- **Missing GraphQL servlet.** When `graphql-dxm-provider`'s servlet is not available, `community.graphql.execute`
  and `community.graphql.introspect` stay listed and refuse each call with `unavailable` and a hint naming
  `graphql-dxm-provider`, instead of disappearing from `tools/list`.
- **Coverage gates.** `mvn verify` fails below 90% JaCoCo line coverage, and `yarn test` fails below the
  Jest thresholds of `jest.config.js`.
- **Troubleshooting guide** in the README.

### Changed

- **Administration UI rebuilt.**
  - The allow list is an accessible tree with arrow keys, Home and End, and Space or Enter to check an item.
  - Queries and mutations are separate groups, each with bulk actions. "Allow all queries" leaves the `admin`
    namespace unchecked.
  - Confirmation before clearing the list, clearing queries while mutations remain, allowing all mutations,
    and each time `query:admin` or a field under it is checked. The rest of the page is inert while a
    confirmation is open.
  - The never-allowed paths are shown but locked: `mutation:communityMcp`, `mutation:admin.jahia.configuration`,
    `mutation:admin.personalApiTokens`, and every mutation path with a `grantRoles` or `revokeRoles` segment.
  - The policy banner shows the policy in force and, with unsaved changes, the policy after save.
  - An expired Jahia session (HTTP 401 or 403) on save or load is reported as such. Unsaved changes are kept
    in the browser tab and restored after logging in again, only if the saved list on the server has not
    changed meanwhile.
  - Entries that are unknown to the schema, locked, or ignored by the server (malformed, or another operation
    type such as `subscription:`) are listed with a Remove action. Locked and ignored entries block Save.
  - The filter matches all root operations and the fields already loaded.
  - An unsaved-changes indicator and a Discard action. The browser warns when you reload or close the page
    with unsaved changes; moving to another administration page does not warn.
  - Persistent, dismissible status messages, and the server's error reason is shown on a failed save.
  - The UI targets WCAG 2.2 Level AAA.
- **Saving.** `saveSettings` fails with a GraphQL error that names the offending entry. An empty list saves the
  read-only default.
- **Response handling.** GraphQL-level errors stay in the `errors` array of a successful result, so clients see them.
- **Skills.** Bundled default skills are seeded only when absent. Editing a bundled file does not update an
  existing installation. `mcpmix:mcp` now carries `jmix:hiddenType`, so skills no longer appear in the
  editors' content-creation pickers. The skills container and its sub-folders stay `jnt:contentList`, as
  created by 1.x, so an upgrade needs no content migration. Editing is restricted by the container's ACL:
  its inheritance is broken on activation, including on a container that a 1.x install created, so only the
  system session (the administrator-gated GraphQL operations and the module's seeding) writes skills.
- **Build.** Built against `mcp-server` 0.5.0-SNAPSHOT. The `graphql-java-servlet` dependency is removed.
- **Bundled skill.** The production recipe of `default/jahia-properties` sets
  `repositoryDirectoryListingDisabled = true` (it wrongly set `false`). Bundled skills are seeded only when
  absent: on an existing installation, update the skill with `saveSkill` or delete it before the upgrade.

### Upgrade notes

- **Stale `.cfg` comments.** An installation upgraded from 1.x keeps its existing `org.jahia.community.mcp.cfg`
  (the shipped file is a default configuration and is not overwritten). Its 1.x comment "If empty, all operations
  are allowed" is stale. An empty whitelist is the read-only default: queries only. Unprefixed 1.x entries are
  read as query-only, so add the `mutation:` entries you need, and add `query:admin…` entries if an empty
  list used to serve `admin` queries.
- **Review the skills.** On the first activation of 2.0 the skills container's ACL inheritance is broken and
  the local role grants on the container and below are removed. Any editor access to skills granted in 1.x
  is gone; skills are written only through the administrator-gated `saveSkill` and `deleteSkill`. Skills
  written in 1.x are kept unchanged: read every skill under `/sites/systemsite/contents/mcp-skills` and
  delete or correct any you did not write, because every administrator's AI client reads them.
- **Clusters.** `saveSettings` writes the OSGi configuration on the node that served the request only. Set the
  whitelist on every node, through the UI or `saveSettings` on each node, or through provisioning or the `.cfg`.
- **Orphan 1.x configuration.** `karaf/etc/org.jahia.modules.PersonalApiToken-community-mcp.cfg` (the 1.x
  `community-mcp` scope) is no longer used. Delete it if it is still present.
- **MCP clients.** Clients that relied on a browser session must send an `Authorization` header.

### Notes

- The 2.0.0 build targets `mcp-server` 0.5.0-SNAPSHOT. Release only against a released `mcp-server` 0.5.x.
- Until 2.0.0 is released, 1.x remains the supported line (see [SECURITY.md](SECURITY.md)).
- Two behaviours of `mcp-server` are relied on but not guaranteed by its tool SPI: the caller bound on the
  worker thread, and the SPI package version, which follows the bundle version. A later `mcp-server` that
  changes the first makes the community tools refuse every call; `mcp-server` 0.6 leaves this bundle
  unresolved until a release supports it.

## [1.0.4] - 2026-09-07

### Security

- The whitelist gate parses documents with graphql-java and refuses any document it cannot parse. The
  former hand-written scanner could be misled by valid syntax such as a leading comma.
- With a whitelist set, named fragment spreads are refused.
- Skill names are validated (no traversal, no empty segment) before any repository access.
- Error messages no longer echo exception text, the request body is capped at 2 MB, `X-Forwarded-For` is no
  longer trusted for the client IP, and unauthenticated requests get HTTP 401.

### Changed

- The GraphQL operations are grouped under one `mcp { … }` namespace on `Query` and `Mutation`.
- Accessibility of the administration UI, and more unit and integration tests.

## [1.0.3] - 2026-04-28

### Changed

- The endpoint moves from `/modules/mcp` to `/modules/community-mcp`, the permission and API scope from `mcp`
  to `community-mcp`, and the configuration files are renamed to match.

## [1.0.2] - 2026-04-27

### Added

- Skills: Markdown instruction documents stored in JCR, served by the `listSkills` and `getSkill` tools.
  Default skills (`hello-jahia`, `jahia-properties`, `jahia-properties-ref`) are seeded on first deployment.

## [1.0.1] - 2026-04-27

### Added

- The `introspectSchema` tool.
- Whitelist access control for `executeGraphQL`, with an administration UI that edits it as an operation tree.
- Docker-based Cypress integration tests.

### Fixed

- Unauthenticated requests get HTTP 401.

## [1.0.0] - 2026-04-06

- First release: an MCP endpoint that runs GraphQL operations in-process through the `executeGraphQL` tool,
  authenticated with personal API tokens.
