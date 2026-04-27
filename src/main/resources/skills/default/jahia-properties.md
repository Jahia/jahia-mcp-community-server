---
mcpName: Jahia 8.2 — Properties Quick Guide
description: Quick guide for configuring Jahia 8.2 via jahia.properties, jahia.node.properties, and OSGi .cfg files. Covers file locations, variable interpolation, production setup, cluster config, and authentication.
---
## Configuration Files

| File | Location | Purpose |
|---|---|---|
| `jahia.properties` | `digital-factory-config/jahia/` | Main Jahia configuration |
| `jahia.node.properties` | `digital-factory-config/jahia/` | Cluster / node-specific settings |
| OSGi `.cfg` files | `digital-factory-data/karaf/etc/` | Module and subsystem settings |

`jahia.properties` is read at startup. Changes require a restart unless overridden via OSGi config at runtime.

## Variable Interpolation

| Variable | Resolves to |
|---|---|
| `${jahiaWebAppRoot}` | Webapp root (e.g. `.../webapps/ROOT`) |
| `${jahia.data.dir}` | Same as `jahiaVarDiskPath` once resolved |
| `${jahia.jackrabbit.home}` | JCR repository home |

Default data layout:
```
digital-factory-data/          ← jahiaVarDiskPath
  modules/                     ← jahiaModulesDiskPath
  imports/                     ← jahiaImportsDiskPath
  repository/                  ← jahia.jackrabbit.home
  repository/datastore/        ← jahia.jackrabbit.datastore.path
  generated-resources/         ← must be shared in cluster
```

## Common Production Setup

```properties
# jahia.properties
operatingMode = production
jahia.find.disabled = true
repositoryDirectoryListingDisabled = false
urlRewriteSeoRulesEnabled = true
urlRewriteRemoveCmsPrefix = true
permanentMoveForVanityURL = true
shiro.blockSemicolon = true
```

## Cluster Setup (jahia.node.properties)

```properties
cluster.activated = true
cluster.node.serverId = my-node-1        # unique per node
processingServer = true                  # only ONE node should be true
cluster.tcp.bindAddress =                # leave empty to auto-detect, or set IP
cluster.tcp.bindPort = 7870
cluster.hazelcast.bindPort = 7860

# In jahia.properties — must be on shared filesystem
jahiaGeneratedResourcesDiskPath = /shared-nfs/generated-resources/
```

## Authentication Quick Reference

| Auth method | Enable property |
|---|---|
| Cookie (default) | `auth.cookie.enabled = true` |
| CAS | `auth.cas.enabled = true` + set `auth.cas.serverUrlPrefix` |
| SPNEGO (Windows) | `auth.spnego.enabled = true` |
| Container | `auth.container.enabled = true` |

CAS minimum config:
```properties
auth.cas.enabled = true
auth.cas.serverUrlPrefix = https://cas.example.com/cas
auth.cas.loginUrl = ${auth.cas.serverUrlPrefix}/login
auth.cas.logoutUrl = ${auth.cas.serverUrlPrefix}/logout
```

## JCR / Search Tuning

```properties
# One-time index repair
jahia.jackrabbit.searchIndex.enableConsistencyCheck = true
jahia.jackrabbit.searchIndex.autoRepair = true

# Full reindex (very slow — use only when needed)
jahia.jackrabbit.reindexOnStartup = true

# Query stats (for diagnosing slow queries)
jahia.jackrabbit.queryStatsEnabled = true
```

## OSGi / Karaf Shell

```properties
karaf.remoteShell.port = 8101        # SSH into Karaf; negative = disabled
karaf.remoteShell.host = 127.0.0.1  # restrict to localhost in prod
```

## Full Property Reference

Call getSkill("default/jahia-properties-ref") for all ~90 properties grouped by category with keys, defaults, and descriptions.
