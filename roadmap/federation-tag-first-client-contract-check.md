---
id: R298
title: "First-client contract-composition check: do type-level @tag-only synthesised connection types satisfy a real federation contract?"
status: Backlog
bucket: bug
priority: 3
theme: pagination
depends-on: []
created: 2026-06-10
last-updated: 2026-06-10
---

# First-client contract-composition check: do type-level @tag-only synthesised connection types satisfy a real federation contract?

R295 propagates the carrier field's federation `@tag` applications onto the synthesised Connection, Edge, and PageInfo **type declarations** (the minimal shape that satisfies contract composition as the bug was framed). Its "Resolved" section deferred a verification step that could not run at spec time and could not run in the implementation/review sandbox either: building a *real* Apollo Federation contract that includes the carrier's tag and confirming composition succeeds against type-level-only tags. The risk this check covers: `TagApplier` tags fields, input fields, enum values, args, and unions but never type declarations (see its class javadoc), so under `<schemaInput tag>` the synthesised types carry the only type-level tags in the graph, while legacy contracts were validated against field-level tags. The green federation-SDL round-trip test in `ConnectionFederationTagPipelineTest` proves the tags are *emitted* on the types, but a round-trip is not a contract build: a real contract can still reject type-level-only tags. The outstanding work: run the first-client contract build; if (and only if) type-level tags prove insufficient, tag the synthesised types' fields (`edges`, `nodes`, `pageInfo`, `totalCount`, `cursor`, `node`) as well. Landed under R295 (`fae7c6f`, `1fdcf18`); this item carries the residual federation-contract risk R295 explicitly could not close in-session.
