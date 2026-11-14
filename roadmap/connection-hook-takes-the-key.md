---
id: R1185
title: "The consumer chooses a query's connection in a hook that takes the whole connection key"
status: Backlog
bucket: feature
priority: 4
theme: service
depends-on: []
created: 2026-11-14
last-updated: 2026-11-14
---

# The consumer chooses a query's connection in a hook that takes the whole connection key

## Goal

A consumer registers one connection hook on the generated runtime. Graphitron hands it the whole `ConnectionKey` a query runs under, and the hook returns the connection. Today a database-per-tenant build is configured with a default `DataSource` beside a `Map<TenantId, DataSource>`, and global reads and `@globalData` services fall back to a default tenant or the default source, a choice graphitron makes and the schema partly states. When this lands, which connection a key gets is the consumer's code, which it owns and can test. A key's parts are each optional, the tenant among them, and a key with an empty tenant part is a key like any other: the hook decides what it means. Today's default-source behaviour stays available for backwards compatibility as what the runtime does when no hook is registered. Because the key is a value, graphitron acquires a connection again only where a child's key differs from its parent's.

## Notes

- The direction is Alf's (2026-11-14), recorded in `docs/architecture/reference/runtime-extension-points.adoc` § Per-tenant routing.
- The generated code already acquires through one point keyed by the whole key (`OwnedConnections.dslFor(env, ConnectionKey)`), for root services and, once R876's child-call node lands, for children; this item puts the consumer's hook behind that point.
- R1119 adds the claims part of the key; the hook receives it with the rest.
- The manual's default-tenant section in `tenant-scoping.adoc` is rewritten by this item.
