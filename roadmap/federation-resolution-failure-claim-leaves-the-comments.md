---
id: R1164
title: "No comment claims federation raises its own error for an unresolved _entities slot"
status: Backlog
bucket: cleanup
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-11-10
last-updated: 2026-11-10
---

# No comment claims federation raises its own error for an unresolved _entities slot

## Goal

Two comments still say that federation raises a resolution-failure error of its own when `_entities`
leaves a slot unresolved, and after this item neither does. That claim is false: a `null` element of
`[_Entity]!` is a legal answer and nothing downstream reports it. It is the belief that kept the
silent `null` for an unknown `__typename` in place until R1076 replaced it with an error at the slot,
and R1076 corrected the two copies its spec named (the dispatcher generator's class javadoc and a test
javadoc). Its In Review -> Done review found two more it did not name:

* `KeyAlternative`'s javadoc, on `resolvable`: "the subgraph declares the key for reference-only and
  federation surfaces its own resolution-failure error". What is true now: the dispatcher skips the
  alternative, a representation matching only it answers `null`, and an entity with no resolvable key
  at all answers `null` with the client error `fetchEntities` raises.
* `GraphitronSchemaClassGenerator`, the comment over the placeholder `fetchEntities` wired when no
  entity is classified: "`_entities([rep])` surfaces federation's \"entity resolution failed\"". Say
  what that path does, after checking whether `_entities` is reachable at all in a schema with no
  entity type.

Comment-only; no behaviour changes.
