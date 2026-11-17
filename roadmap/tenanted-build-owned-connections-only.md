---
id: R1195
title: "A build whose connection key has a tenant part has no escape-hatch factory"
status: Backlog
bucket: cleanup
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-11-17
last-updated: 2026-11-17
---

# A build whose connection key has a tenant part has no escape-hatch factory

## Goal

A build whose generated `ConnectionKey` has a tenant part emits no `Graphitron.newExecutionInput(dsl, ...)`, as an accepting build already does not, so a keyed build runs on owned connections only and a consumer cannot hand a tenanted request a single `DSLContext` that answers every key. R1119's ruling says a build whose key has parts is owned connections only; slice 8d.2 enforced it for vouched-for parts and left the tenant part, where the escape hatch still compiles and fails at the first routed field.
