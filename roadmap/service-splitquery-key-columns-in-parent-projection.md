---
id: R425
title: "Parent projection omits key columns a @splitQuery/@service child needs, so its dataloader key is silently null"
status: Backlog
bucket: bug
priority: 8
theme: service
depends-on: []
created: 2026-07-02
last-updated: 2026-07-02
---

# Parent projection omits key columns a @splitQuery/@service child needs, so its dataloader key is silently null

A `@splitQuery` child field (both the `@service` DataLoader shape and, by the same
mechanism, `@reference` split children) builds its DataLoader key from the parent source
record: `((Record) env.getSource()).into(<ParentTable>)`. The service/rows-method then
reads the node key columns off that record to build its `WHERE <keyCol> IN (...)`. But the
parent's SELECT projection is driven purely by the GraphQL selection set
(`<ParentType>.$fields(env.getSelectionSet(), ...)`), which projects only the columns for
fields the client actually selected. The key columns a child needs are an *implicit
dependency* of the child field that the codegen does not guarantee. When the client does
not select a field that maps to the key column, the column is absent from the parent row,
`.into(...)` yields a record with that key column `null`, and the child query runs
`WHERE <keyCol> IN (null)`. The child field then resolves to `null` silently, with no
error. This is a data-correctness bug that bites hardest under federation: an Apollo
Router entity fetch selects only the fields it needs and supplies key columns via
`representations` (not re-selected in the sub-selection), which is exactly the "client
selects the child but not the key" shape.

## Reproducer

`AdmissioOrganisasjon` `@node(typeId: "OpptakOrganisasjon", keyColumns: ["organisasjonskode"])`
with `navn`/`forkortelse` as `@splitQuery @service`
(`OversatteTeksterService.opptakOrganisasjonNavn/Forkortelse`). Generated
`AdmissioOrganisasjonFetchers.navn` (RC23):

```java
OrganisasjonRecord key = ((Record) env.getSource()).into(Tables.ORGANISASJON_);
return loader.load(key, env)...
// loadNavn -> new OversatteTeksterService(dsl).opptakOrganisasjonNavn(keys)  // WHERE organisasjonskode IN (...)
```

A selection of `{ navn { nb } forkortelse { nb } }` without `id`/`organisasjonskode`
(both map to `ORGANISASJONSKODE`) omits `ORGANISASJONSKODE` from the parent projection,
so `key.getOrganisasjonskode()` is `null` and `navn`/`forkortelse` come back `null`.
Selecting `id` alongside masks it. The opptak reproducer is
`MegSomSokerQueryIT.organisasjonNavnVedEntityFetchUtenIdSelektert` (which currently cannot
run: it fires a raw `_entities` query at the Apollo Router, which does not expose
federation-internal fields; it must be retargeted to the subgraph in-process, where
`Graphitron.buildSchema` exposes `_entities`, to turn red on the null rather than 400).

## Root cause

The parent-type projection builder emits column selections only for GraphQL-selected
fields and does not force-include the node key columns required to reconstruct a child
`@splitQuery` field's DataLoader key. The key-column set is known at build time (from the
parent type's `@node keyColumns` / the child's key derivation), so the projection can be
augmented deterministically whenever a split child is present in the selection.

## Scope / direction (to be firmed up at Spec)

When a selection set includes a `@splitQuery` child whose key is reconstructed from the
parent record (`.into(<ParentTable>)`), ensure the parent projection always includes that
child's key columns, even when no GraphQL-selected field maps to them. Prefer augmenting
the projection over changing the key-construction shape, so the DataLoader key is built
from real column values rather than a synthesized record. Confirm the `@service` shape and
the split-`@reference` shape both go through the same fix. Add execution coverage for a
`@splitQuery @service` child selected *without* its key field in the selection (the
existing coverage always selects the id, which masks the bug). Distinct from R424, which
concerns child *arguments* read from the wrong `env`; this concerns child *key columns*
missing from the parent *projection*.

Discovered via an opptak-subgraph reproducer on graphitron 10.0.0-RC23 (branch
`reproduser-null-i-underfelter`).
