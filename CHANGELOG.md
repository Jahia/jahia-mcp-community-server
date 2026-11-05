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
  `forbidden`, `validation`, `not_found` and `internal`. The 1.x text format is gone.
- **Authentication.** The token scopes `graphql` and `community-mcp` are gone. Any token accepted by
  `mcp-server` works, and the caller must hold `admin` on the repository root.
- **Read-only default.** An empty whitelist no longer allows every operation. It allows every query and
  refuses every mutation and subscription.
- **Whitelist syntax.** Entries are `query:<path>` or `mutation:<path>`. An unprefixed entry is accepted as
  a legacy **query-only** entry and logged at WARN. Malformed entries grant nothing.
- **Whitelist configuration.** `org.jahia.community.mcp` is a required (`REQUIRE`) configuration. Without it
  the gated tool and the settings API are unavailable (fail closed) instead of defaulting to a permissive state.
- **GraphQL namespace.** `mcp { … }` → `communityMcp { … }`. Types are `CommunityMcpQuery`,
  `CommunityMcpMutation`, `CommunityMcpSettings` and `CommunityMcpSkill`.
- **Skill key.** `skills { name }` and `community.skill.list` return the path relative to the skills root
  (for example `default/hello-jahia`), the same key that `saveSkill`, `deleteSkill` and
  `community.skill.get` accept. Before, the name was the bare node name.
- **Removed.** The `GET /modules/community-mcp` health check, the 2 MB request cap of the 1.x endpoint, and
  the `community-mcp` API scope.
- **Dependencies.** Requires `mcp-server` 0.5.x (`[0.5,0.6)`) and Declarative Services 1.4 (Felix SCR 2.1 or later).
- **Exports.** Only `org.jahia.community.mcp.graphql` is exported. The initial-content import
  (`src/main/import`) is removed.

### Security

- **Administrator gate on every tool.** The check uses the caller bound by `mcp-server` (same username and
  realm) and the JCR `admin` permission on `/`. It fails closed on any missing binding, mismatch or
  repository error. All tools run through one template method, so none can skip the gate or the audit event.
- **No credential forwarding.** The in-process GraphQL call carries no `Authorization` header. It runs as the
  bound caller, with JCR ACLs applied.
- **Refused by design.** `mutation:communityMcp` and `mutation:admin.jahia.configuration` (each with everything
  under it) are always refused by the gate, and `saveSettings` refuses entries for them. The data plane cannot
  rewrite its own guard, or this module's configuration through the generic OSGi configuration mutation.
- **Unparseable documents** are refused in every state (SEC-364). **Named fragment spreads** are refused in every
  state, including the read-only default (SEC-2). Inline fragments are supported.
- **Whole-document check.** Every operation in a document is checked, so a mutation cannot hide behind an
  allowed query.
- **Intermediate mutation fields run.** A deeper mutation entry (for example `mutation:jcr.addNode.mutateProperty`)
  also runs the mutation fields above it, because GraphQL cannot run the deeper field without them. Choose entries
  accordingly and prefer leaf paths.
- **Bounded introspection.** At most 25 types per call and 100,000 characters of output. Type names are passed as
  GraphQL variables, never formatted into the document.
- **Bounded responses.** In-process GraphQL responses are capped at 5 MB.
- **Skill container.** The skills container has its ACL inheritance broken. Skills are written only through the
  administrator-gated operations and the module's own seeding, which run as the system session. Skill node
  types are checked before reads, updates and deletes.
- **Audit.** Each call records `tool.execute.ok` or `tool.execute.error`. `community.graphql.execute` records a
  SHA-256 `queryHash`, never the document text or the variables.
- **Accepted risks** (documented, not fixed): API scopes are not applied to in-process calls (SEC-4); a prefix
  entry grants everything below it (SEC-6).

### Added

- **Whitelist state.** Snapshot-based, immutable and published atomically on configuration change. An empty
  whitelist is a documented read-only default.
- **Tool descriptions.** Each tool describes its safety, its refusals and where to look next (`community.graphql.introspect`
  first, the built-in tools when they fit). Skill text is tagged `reads-ugc`.
- **Introspection.** `community.graphql.introspect` returns `skippedTypes` and `truncatedTypes` so callers can
  request the rest.
- **Skill management.** `saveSkill` creates intermediate folders for nested keys.
- **Troubleshooting guide** in the README.

### Changed

- **Administration UI rebuilt.**
  - The allow list is an accessible tree with arrow keys, Home and End, and Space or Enter to check an item.
  - Queries and mutations are separate groups, each with bulk actions.
  - Confirmation before clearing the list, clearing queries while mutations remain, and allowing all mutations.
  - The never-allowed paths, `mutation:communityMcp` and `mutation:admin.jahia.configuration`, are shown but locked.
  - Entries that are unknown to the schema, or locked, are listed with a Remove action.
  - The filter matches all root operations and the fields already loaded.
  - An unsaved-changes indicator and a Discard action.
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

### Upgrade notes

- **Stale `.cfg` comments.** An installation upgraded from 1.x keeps its existing `org.jahia.community.mcp.cfg`
  (the shipped file is a default configuration and is not overwritten). Its 1.x comment "If empty, all operations
  are allowed" is stale. An empty whitelist is the read-only default: queries only. Unprefixed 1.x entries are
  read as query-only, so add the `mutation:` entries you need.
- **Clusters.** `saveSettings` writes the OSGi configuration on the node that served the request only. Set the
  whitelist on every node, through the UI or `saveSettings` on each node, or through provisioning or the `.cfg`.

### Notes

- The 2.0.0 build targets `mcp-server` 0.5.0-SNAPSHOT. Release only against a released `mcp-server` 0.5.x.
- Until 2.0.0 is released, 1.x remains the supported line (see [SECURITY.md](SECURITY.md)).
