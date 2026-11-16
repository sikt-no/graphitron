---
id: R1192
title: "A fan-out worker releases its connection lease when it finishes"
status: Backlog
bucket: architecture
priority: 4
theme: runtime-connection
depends-on: [signed-reference-entities-across-subgraphs]
created: 2026-11-16
last-updated: 2026-11-16
---

# A fan-out worker releases its connection lease when it finishes

## Goal

A fan-out over a request's tenants holds only the connections its running workers need. Under R1119's
connection contract Graphitron acquires one lease per distinct connection key and releases every lease
when the operation ends, so a field fanned out over N tenants holds N connections from the consumer's
pools until the whole operation is done. When this lands, a fan-out worker's lease is released as soon
as that worker's fetch completes, while acquisition stays once per key within the fan-out; a consumer
with many tenants, sis among them, stops holding a connection per tenant for the rest of the request.
The connection contract's shape does not change: the source still sees one `acquire` and one release
per key, only sooner.
