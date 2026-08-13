---
id: R657
title: "List-cardinality accessor keys on the @service path"
status: Backlog
bucket: feature
priority: 5
theme: service
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# List-cardinality accessor keys on the @service path

An accessor returning `List<XRecord>` fans one parent out to many batch keys. On the table-child path that is the ordinary `LOAD_MANY` dispatch; on the `@service` path the `Map<Key, Value>` return contract assumes one key per parent, so the many case is rejected by name at classify time ("a child @service batches one key per parent") rather than silently keying on the first element.

What is missing is the fan-in design: what the service is asked to return when one parent owns several keys, how the loaded values are recombined per parent, and whether the field's own cardinality or the accessor's decides the shape the author sees. The rejection is deliberate and actionable in the meantime (expose a single-record accessor, or key on a table the parent produces one of), so this is a widening rather than a gap in the shipped behaviour.
