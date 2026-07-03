---
id: R425
title: "Parent projection omits key columns a @splitQuery/@service child needs, so its dataloader key is silently null"
status: In Progress
bucket: bug
priority: 8
theme: service
depends-on: []
created: 2026-07-02
last-updated: 2026-07-03
---

# Parent projection omits key columns a @splitQuery/@service child needs, so its dataloader key is silently null

> Collapse the two `Split*` arms of `TypeClassGenerator.collectRequiredProjectionColumns`
> into a single `BatchKeyField` capability arm returning `sourceKey().columns()`, so the two
> `@service` DataLoader shapes (`ServiceTableField`, `ServiceRecordField`) get their key
> columns force-included in the parent `$fields` projection exactly as `Split*` children
> already do. Guard the record-parent `BatchKeyField` implementers with a loud throw (they
> must never reach a table-parent `$fields`). Investigation finding: split-`@reference`
> children are *already* force-included; the gap is `@service`-only. Pin at pipeline tier
> via `TypeSpecAssertions.appendsRequiredColumn` and at execution tier with an unmasked
> fixture (a `@service` child on a parent with no force-projecting siblings, queried without
> its key field) plus a representations-driven `_entities` test (the federation reproducer
> shape). Companion hazard on the developer side of the contract filed as R426.

## Problem

A `@splitQuery` child field (the `@service` DataLoader shape; plain `@reference` split
children share the mechanism but turn out to be already covered, see Root cause) builds
its DataLoader key from the parent source record: `((Record) env.getSource()).into(<ParentTable>)`. The service/rows-method then
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

### Reproducer

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

The force-include mechanism already exists and already covers the split shapes; the gap is
a pattern-match omission for the two `@service` shapes.

`TypeClassGenerator` emits one static `$fields(sel, table, env)` method per
`TableType`/`NodeType` (`generate()`, `TypeClassGenerator.java:51-59`). The selection
switch projects GraphQL-selected columns; then every column in `requiredProjectionColumns`
is appended unconditionally (`build$FieldsMethod`, `:239-241`). That set comes from
`collectRequiredProjectionColumns` (`:344-361`), which matches:

- `ChildField.SplitTableField` / `SplitLookupTableField` → `sourceKey().columns()` (so
  split-`@reference` children, contrary to the Backlog framing, are already covered);
- `ChildField.TableMethodField` (single-hop `FkJoin`) → FK source-side columns;
- `ChildField.NestingField` → recursion;
- everything else, **including `ServiceTableField` and `ServiceRecordField`**, falls
  through to `Stream.empty()`.

Both `@service` shapes carry the identical `SourceKey` contract via the `BatchKeyField`
capability interface (`model/BatchKeyField.java`), and their generated fetchers read
exactly `sourceKey().columns()` off the parent source record through the shared
`GeneratorUtils.buildKeyExtraction` (`:492-524`): `Wrap.Row` emits
`((Record) env.getSource()).get(Tables.T.COL)` per column (fails *loudly*, jOOQ throws on
an absent field), `Wrap.Record` emits `.into(cols...)` and `Wrap.TableRecord` emits
`.into(Tables.X)` (both fail *silently*, absent columns come back null and the child
resolves null). The key-column set is fixed at classification time: for a table-bound
parent, `FieldBuilder.classifyChildFieldOnTableType` passes
`tableType.table().primaryKeyColumns()` into `ServiceDirectiveResolver.resolve`
(`FieldBuilder.java:5881`), which lands verbatim in `SourceKey.columns`
(`buildServiceTableSourceKey` / `buildServiceRecordSourceKey`, `FieldBuilder.java:244-275`).
Projecting `sourceKey().columns()` is therefore correct by construction: it is the same
set, from the same model object, that the key extraction reads.

Every parent-side SELECT that feeds a `@service` child's `env.getSource()` goes through
`$fields`: root and child fetchers (`TypeFetcherGenerator`), the node fetcher
(`QueryNodeFetcherClassGenerator`), and the federation entity dispatch
(`EntityFetcherDispatchClassGenerator` / `SelectMethodBody:112`). Fixing the one collector
fixes all of them, including the representations-driven `_entities` fetch that surfaced the
bug.

**Why in-tree coverage never caught it:** the sakila `Film` type carries
`cast`/`castByKey` `@splitQuery @reference` children whose `SourceKey` force-projects
`FILM_ID`, so every existing `Film` service-child execution test
(`films_titleLowercase_...`, `films_titleTitlecase_...`, etc.) passes only via that
unrelated sibling. They exercise a valid scenario (service child with a projecting
sibling) but do not pin this behaviour: a regression of this fix would leave them green.

## Design

### Production change: one capability arm plus a loud guard

In `collectRequiredProjectionColumns`, replace the two explicit `Split*` arms with a single
capability arm, keeping the `TableMethodField` and `NestingField` arms unchanged:

```java
if (f instanceof ChildField.RecordTableField
        || f instanceof ChildField.RecordLookupTableField
        || f instanceof ChildField.RecordTableMethodField)
    throw new IllegalStateException(...); // record-parent variant in a table-parent $fields walk
if (f instanceof BatchKeyField bk)
    return bk.sourceKey().columns().stream();
```

`BatchKeyField` is the model's own name for "DataLoader-backed field carrying source-side
key metadata", and its javadoc directs generators to pattern-match on it; the projection
step asks a question that is uniformly true across its variants ("which parent-row columns
does your key extraction read"), which is the capability-interface case per the
capability-vs-sealed-switch principle. The rejected alternative, two more explicit variant
arms, keeps the enumeration style but leaves the capability un-collapsed; that enumeration
already has a blind spot today (there are *seven* `BatchKeyField` implementers, not the six
its javadoc lists; `RecordTableMethodField` is missing), which is evidence against
maintaining parallel lists.

**The soundness invariant lives at the call site, not the variant.** `SourceKey.columns()`
is parent-side *or* target-side depending on shape (dispatch-axes.adoc); what makes the
blanket `sourceKey().columns()` projection correct is that `collectRequiredProjectionColumns`
runs only under `generate()`'s `TableType`/`NodeType` filter, so every `BatchKeyField` it
sees classified on a table-backed parent and carries parent-side columns read by
`buildKeyExtraction`. The three record-parent implementers (`RecordTableField`,
`RecordLookupTableField`, `RecordTableMethodField`) key off a Java accessor via
`buildRecordParentKeyExtraction` instead and can carry target-aligned columns; if one ever
leaked into this walk, the blanket arm would silently project wrong columns, the same
silent-null family this item fixes. Hence the guard throws rather than returning
`Stream.empty()`: a leak fails at generation time, not at runtime with a null key. State
this invariant in the guard's comment.

### Fold-ins

- Correct the `BatchKeyField` javadoc implementer list (`model/BatchKeyField.java:8-11`) to
  include `RecordTableMethodField`.
- Update the two prose comments describing the force-include taxonomy
  (`TypeClassGenerator.java:98-108` at the collection site, `:234-238` at the emission
  site) to name the capability instead of enumerating `Split*`.

### Non-direction (per Backlog, confirmed)

Augment the projection; do not change the key-construction shape. The DataLoader key keeps
being built from real projected column values. No synthesized records, no per-field
re-fetch.

## Tests

Per the tier rubric (pipeline pins shape, execution pins behaviour; body-string assertions
banned):

- **Pipeline-tier** (`graphitron`, sibling of `TableMethodFieldPipelineTest`, which
  already pins the force-include for `@tableMethod` via
  `TypeSpecAssertions.appendsRequiredColumn`): SDL with a `@service` child on a table type
  that has *no* other force-projecting children; assert
  `appendsRequiredColumn(<parent PK column>)` on the generated type class. One test per
  service shape family: a table-bound return (`ServiceTableField`) and a scalar return
  (`ServiceRecordField`). A nested variant (service child under a `NestingField`) pins the
  recursion path. The record-parent guard is defensive and unreachable through
  classification; it gets no pipeline fixture.
- **Execution-tier** (`graphitron-sakila-example`): new unmasked fixture, a `@service`
  child on a parent type carrying no `@splitQuery`/`@tableMethod` siblings (pick or add a
  lean table-backed type; `Film` cannot turn red). Query the service child *without*
  selecting any field that maps to the key column; assert the correct non-null value
  against real PostgreSQL. Cover the `Wrap.TableRecord` source (the silent-null reproducer
  shape) and one `Row`/`Record1` source (the loud-throw shape). Add a comment on the
  existing `Film` service-child tests stating they do not pin this behaviour (masked by the
  `cast`/`castByKey` force-include) and pointing at the unmasked fixture.
- **Federation execution-tier** (`FederationEntitiesDispatchTest` sibling): an `_entities`
  representations-driven fetch selecting *only* the service child (key supplied via the
  representation, not re-selected in the sub-selection), the exact Apollo Router shape from
  the opptak reproducer. Requires the unmasked fixture type to be an entity (`@key`) in the
  federated generation.
- **Compilation-tier**: the sakila-example compile covers the emitted projection code; the
  new fixtures make it exercise the new arm.

## Implementation sites

- `generators/TypeClassGenerator.java`: `collectRequiredProjectionColumns` (capability arm
  + guard), taxonomy comments at `:98-108` and `:234-238`.
- `model/BatchKeyField.java`: javadoc implementer-list correction.
- `graphitron/src/test/.../<new>ServiceProjectionPipelineTest` (or fold into an existing
  service pipeline test class if one fits better).
- `graphitron-sakila-example` schema + query/federation tests per the test plan.

## Non-goals

- Record-parent shapes (`RecordTableField`, `RecordLookupTableField`,
  `RecordTableMethodField`): no `$fields` projection feeds their key reads; guarded, not
  routed.
- The developer-side `Wrap.TableRecord` contract hazard (a service body reading *non-key*
  columns off the partial record): filed separately as R426.
- No restructuring of the existing masked `Film` fixtures; they keep covering the
  with-projecting-sibling scenario.
- No change to `@node keyColumns` handling or key derivation; the fix consumes
  `sourceKey().columns()` as classified.
- Retargeting the opptak `MegSomSokerQueryIT` reproducer lives in the opptak repo, not
  here.

Distinct from R424, which concerns child *arguments* read from the wrong `env`; this
concerns child *key columns* missing from the parent *projection*.

Discovered via an opptak-subgraph reproducer on graphitron 10.0.0-RC23 (branch
`reproduser-null-i-underfelter`).
