# Security Policy

## Supported Versions

2.x is the supported line and receives security fixes. 1.x is no longer supported: upgrade to 2.x,
which requires `mcp-server` 0.5.x (see [Migrating from 1.x](README.md#migrating-from-1x)).

| Version | Supported |
|---|---|
| 2.x | Yes |
| 1.x | No |

## Reporting a Vulnerability

Security information can be found in our [security.txt file](https://academy.jahia.com/.well-known/security.txt).

Security-relevant design decisions, and the risks accepted for them, are listed in the
[README security notes](README.md#security-notes) and in [CLAUDE.md](CLAUDE.md#accepted-risks). In
particular, the read-only default of `community.graphql.execute` still exposes every query outside the
`admin` namespace to the AI client; production installations should set an explicit allow list.
