---
id: R458
title: "Per-participant explicit join paths on multi-table interface/union child fields"
status: Backlog
bucket: architecture
priority: 3
theme: interface-union
depends-on: []
created: 2026-07-09
last-updated: 2026-07-09
---

# Per-participant explicit join paths on multi-table interface/union child fields

Single-cardinality multi-table polymorphic child fields (a field returning an interface or union backed by several participant tables) only support one join shape today: a single foreign key auto-discovered per participant from the participant's table back to the parent table. R452 closes the silent-wrong-data hole by *rejecting* every richer shape at build time. This item is the deferred capability R452's rejection points at: letting an author state a join path *per participant*, so multi-table child fields can serve the shapes auto-discovery cannot.

Four cases need it:

- **Multi-FK disambiguation.** A participant table with more than one FK back to the parent (auto-discovery finds two and fails; the author must pick which FK correlates the child).
- **Condition joins.** A participant correlated to the parent by a non-FK predicate (`@reference(path: [{condition: ...}])` on a single-table field today).
- **Multi-hop key chains.** A participant reached through an intermediate join table rather than a direct FK.
- **Same-table self-FK participants.** A participant whose table equals the parent/hub table, where `parsePath` derives no correlation (R452 rejects this with a cause-specific deferred message keyed to this item).

## Open design question: syntax

A field-level `@reference` cannot express this: `FieldBuilder.resolveChildPolymorphicJoinPaths` parses the field's single `@reference` path once per participant, applying the same stated path against each participant's own table, so one stated path can be terminal-correct for at most one participant. Per-participant correlation needs a construct that binds a distinct path to each participant type.

The retired legacy `@multitableReference(routes:)` directive was exactly that mechanism (a `@reference`-shaped entry per implementation type); it was deliberately hard-removed (see `docs/manual/reference/directives/multitableReference.adoc`). Whether to revive a per-route directive, extend `@discriminate`/`@discriminator`, or introduce new syntax is the core design fork this item has to resolve before implementation. The prior art and the reasons it was retired both belong in the eventual Spec body.

## Relationship to R452

R452 (the build-time gate) is the predecessor: its rule 1b (same-table participant) uses the deferred-rejection arm carrying this item's slug, and its rule 1c wraps auto-discovery FK-count failures on these fields with a pointer here rather than the generic "add a `@reference` directive" steer. When this item ships, those two rejection sites become the entry points authors follow to the new capability.

