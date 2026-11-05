# Security Policy

## Supported Versions

Until 2.0.0 is released, 1.x is the supported line and receives security fixes. From the release of
2.0.0, 2.x is supported and 1.x is no longer supported. 2.x requires `mcp-server` 0.5.x: upgrade to 2.x
once 2.0.0 and a released `mcp-server` 0.5.x are both available.

| Version | Supported |
|---|---|
| 1.x | Yes, until 2.0.0 is released. No after that |
| 2.x | Once 2.0.0 is released. It is not released yet |

## Reporting a Vulnerability

Security information can be found in our [security.txt file](https://academy.jahia.com/.well-known/security.txt).

Security-relevant design decisions, and the risks accepted for them, are listed in the
[README security notes](README.md#security-notes) and in [CLAUDE.md](CLAUDE.md#accepted-risks). In
particular, the read-only default of `community.graphql.execute` still exposes every query outside the
`admin` namespace to the AI client; production installations should set an explicit allow list.
