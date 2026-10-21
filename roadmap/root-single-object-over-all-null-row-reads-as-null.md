---
id: R1080
title: "A nullable root field returning one object over a row of nulls resolves to null, as v9 resolved it"
status: Spec
bucket: bug
priority: 6
theme: codegen-correctness
depends-on: [table-child-with-all-null-columns-reads-as-null]
created: 2026-10-20
last-updated: 2026-10-21
---

# A nullable root field returning one object over a row of nulls resolves to null, as v9 resolved it

## Goal

A nullable field of `Query` that returns one `@table` object resolves to `null` when the row it
finds would hold nothing under R1069's presence rule: every column the type reads is `null`, every
single reference in it finds no value, and every list in it is empty. This is how Graphitron 9
resolved it, so a client that reads `film(id: 1) != null` as "has a film" keeps working across the
upgrade. A field declared non-null always resolves to an object, as today, and a root read that
finds no row resolves to `null`, as today.

The field kind is `QueryField.QueryTableField` with a single wrapper, whatever its source: a plain
read anchored on the type's table, a `@lookupKey` read, or a `@routine` chain. After this item the
rule R1069 states for child fields holds for these root fields too, and the root divergence R1069's
migration guide lists goes.

```graphql
type Query {
    kontaktinfo(personId: ID!): Kontaktinfo @lookupKey   # null over a row of nulls, as in v9
    hovedkontakt: Kontaktinfo!                           # always an object, as today
}
type Kontaktinfo @table(name: "PERSON") {
    epost: String @field(name: "EPOST_PRIVAT")
    telefon: String @field(name: "TELEFON_MOBIL")
}
```

| Shape | Data | Graphitron 9 | Today | After |
|---|---|---|---|---|
| `kontaktinfo(personId: 7) { epost }` | the `PERSON` row exists, both columns `null` | `null` | `{epost: null}` | `null` |
| `kontaktinfo(personId: 7) { __typename }` | as above | `null` (every field counted) | `{__typename: "Kontaktinfo"}` | `null` |
| `kontaktinfo(personId: 8) { epost }` | no `PERSON` row 8 | `null` | `null` | `null` |
| `hovedkontakt { epost }` | a row with both columns `null` | object | object | object |

No consumer has reported the root shape; the item exists so the divergence has a home, and it is
cheap once R1069's per-type fold exists. In practice it bites only types that map neither a
`@nodeId` nor a never-null column such as the primary key, since R1069's rule never collapses those
while their row exists, and most root single reads are of such types.

## Graphitron 9's behavior

`FetchMappedObjectDBMethodGenerator.getSelectRowOrField` (on the `graphitron-9-head` branch) built a
root read of a table type through the same `FetchDBMethodGenerator.generateSelectRow` it used for
children, and `createMappingFunction` wrapped that row in `Functions.nullOnAllNull(<Type>::new)`
unless the field was non-null. So a nullable root object over a row whose values were all `null`
mapped to `null`, and a non-null one mapped to an object. R1069 records why the rewrite decides over
every field of the type rather than the fetched ones, and where that departs from Graphitron 9; the
same departures hold here and need no further entry in the migration guide.

## Scope

In scope: a single, nullable `QueryTableField`, over any of its three sources
(`LaunchSource.AnchorTable`, `LaunchSource.KeyedLookup`, `LaunchSource.RoutineChain`).

Out of scope, each returning what it fetches as today:

- A root field returning an interface or union (`QueryTableInterfaceField`, `QueryInterfaceField`,
  `QueryUnionField`, `QueryNodeField`), and a `@service` root field. These are R1069's exclusions,
  for the same reasons.
- A mutation field's payload read (`LaunchSource.ProjectedReentry` and its siblings). The row it
  reads is one the mutation just wrote or matched, and no consumer has asked; whether Graphitron 9
  collapsed it was not checked. A consumer who finds a divergence there files it.
- A list element over a row of nulls, at the root or in a child. Graphitron 9's
  `generateSelectRow` mapping ran per row of a list read too, so a list of a table type could hold
  `null` elements there; neither this item nor R1069 restores that. It is named here so it is not
  mistaken for covered, and it becomes a Backlog item if a consumer meets it.

## Implementation

Everything below reads R1069's model and rendering pieces rather than adding parallel ones: the
per-type fold `GraphitronSchema.presenceOf(typeName)`, `NullContract.of`, the `PresenceProbe`
command and its renderer in `PathFragments`. Where R1069 lands under different names, this plan
follows them.

**The command: presence rides the single result shape.** `ResultShape.SingleRecord` gains
`List<ColumnRef> presence` and `Optional<PresenceProbe> probe`, both empty when the field always
resolves to an object. The shape is the right carrier for the same reason `ResultShape` gives for
carrying the ordering: the fact co-varies with cardinality (a list or a connection has no single
object to collapse), so a slot on the command or on each source would make an illegal pair
representable. The producer, `LauncherCommands.resultShapeOf`, fills it from
`NullContract.of(qtf.returnType().wrapper(), schema.presenceOf(typeName))`: `AlwaysObject` (a
non-null field, or a type whose leaves block) gives the empty slots, `CollapsesWhenAbsent` gives its
column leaves and, when it has probes, one `PresenceProbe`. Every other producer of `SingleRecord`
(the DML and pivot reads, the discriminated root, the batched single child) passes empty slots, and
`LauncherCommand`'s constructor gains a source-by-result backstop beside the existing ones:
non-empty presence only on the three sources in scope. If R1069's reviewer moves the batched single
child's slots from `LaunchSource.Correlated.Projected` onto `SingleRecord`, the backstop admits that
source too and the two items share one slot.

**Projection.** In the three single arms of `RootLauncherRenderer` (the anchor arm's `selectChain`,
`lookupBody`'s single branch, and the routine arm, which shares `selectChain`), a non-empty
presence turns the inline `$project(...)` select into a `selectFields` local, the way
`lookupBody`'s list arm already adds `__idx__`: the projection, then each presence column read off
the projected node's alias, then the probe column, rendered by R1069's `PathFragments` probe
renderer correlated on that same alias, under a new `ReservedAliases.PRESENCE` alias. The
correlated alias is the table local for the anchor and lookup arms, and the chain's terminus alias
(the connection arm's `aliasLocal`) for the routine arm. Columns are projected whatever the query
selects, so the decision is selection-independent, as it is for children. A presence column the
projection also selects is selected twice under one name, as `CallWrap.SameRow`'s presence columns
already are beside the same-row child's `$project`.

**The check lives in the launcher.** After `fetchOne()`, the arm binds the record, returns `null`
when it is `null` (no row, as today), and otherwise runs the presence check: present when any column
leaf is non-null or the probe column is set, else `null`. The check is R1069's
`FetcherEmitter.presenceCheck`, lifted into a `render` fragment (`PresenceFragments.check`) that
both `FetcherEmitter` and `RootLauncherRenderer` call, so a child and a root read decide presence
with one emitter. It renders plain `if` statements, since generated code targets Java 17. Placing
it in the launcher rather than the entry point keeps `RootLauncherRenderer.valueTypeOf` and
`TypeFetcherGenerator.buildQueryTableFetcher` unchanged: the launcher still returns a `Record` or
`null`, now with `null` meaning "nothing to show" rather than only "no row". A launcher serves one
coordinate, so unlike R1069's shared batched rows method there is no `T` and `T!` site to keep
apart. The fanned tenancy needs nothing: `LauncherCommand` already restricts it to `RecordList`.

**Docs.** R1069's manual section "When a single object resolves to `null`" in
`docs/manual/how-to/map-types-to-tables.adoc` is reworded from "a nullable field of a `@table`
type" to "a nullable field returning one `@table` object, on a table type or on `Query`", and its
closing paragraph narrows to "A field returning an interface or union, a `@service` field and a
mutation's payload return what they fetch, so a row of nulls there is an object." In
`docs/manual/how-to/migrating-from-legacy.adoc`, R1069's bullet "A field of `Query` returning one
table object stays an object over a row of nulls, where Graphitron 9 returned `null`" is deleted.
`ResultShape.SingleRecord`'s javadoc names the new slots.

## Cost

A nullable single root read of a collapsing type projects its column leaves always, a few columns
of a row the statement already reads, plus one guarded `EXISTS` per probe term, the same cost
R1069 accepts for a child. A type that maps its primary key or a `@nodeId` still projects its
leaves, though the check can never fire while its row exists; R1069's fold does not prune those, and
this item does not either, since the shared fold should answer one question the same way at every
site. If the sakila example's generated SQL shows the extra columns matter, pruning a type whose
leaves include a never-null column is a follow-up for both items.

## Tests

- **Pipeline** (a new `RootNullContractPipelineTest`, beside R1069's
  `TableChildNullContractPipelineTest`): the `SingleRecord` a nullable `QueryTableField` gets over
  each source (anchor, `@lookupKey`, `@routine` chain) carries the type's column leaves and, for a
  type holding a reference, a probe; a non-null field, a list and a connection carry none; a
  `QueryTableInterfaceField` and a DML payload read carry none. The `LauncherCommand` backstop
  rejects presence on a `ProjectedReentry` source. Assertions are on the commands, not on generated
  method bodies.
- **Execution** (`graphitron-sakila-example`, over R1069's fixture table whose rows exist with every
  mapped column `null`, through new `Query` fields):
  - a nullable anchored single read and a nullable `@lookupKey` single read of an all-null row
    resolve to `null`; a row with one non-null column is an object; a missing row is `null` as
    today;
  - `{ __typename }` agrees with the full selection, for both;
  - a non-null root field over an all-null row is an object;
  - a root type of references whose join column is set and whose target row is missing resolves to
    `null` (the probe at the root, correlated on the root's table local);
  - a type with a `@nodeId` field never collapses while its row exists;
  - the read issues one statement, as today (counted, as existing root tests count them).
- The `@routine` chain source is covered at the pipeline tier only: its arm composes through the
  same `selectChain` the anchor arm's execution cases exercise, differing only in the correlated
  alias, and the pipeline case pins that alias. If the sakila fixtures gain a routine over the
  fixture table for another reason, an execution case is cheap to add.
- Existing root single-read tests (`RootLauncherRendererTest.singleRecordShape_fetchOneAndNoOrderBy`
  and the sakila `Query` lookups) stay green unchanged, since their types map a primary key.

## Retired vocabulary

- `ResultShape.SingleRecord()`, the nullary form (the record gains components; the name stays).
- In the manual: "A field of `Query` ... return what they fetch" for a `Query` field returning one
  table object.

## Other solutions we've considered

- **The check in the entry point** (`buildQueryTableFetcher`), on `payload`. It would leave the
  launcher untouched, but the entry point would then need the presence columns' names, which only
  the launcher's select list mints, and the fanned and single-tenant forks of the entry point would
  each have to know whether a check applies. The launcher already owns the statement and its read.
- **Slots on each in-scope `LaunchSource`** (`AnchorTable`, `KeyedLookup`, `RoutineChain`), as
  R1069 does for `Correlated.Projected`. Three records gain the same two components and each list
  arm must pass empty ones; the fact belongs to cardinality, not to where the rows come from.
- **Wrapping the root in a `Multiset` of one row**, so the child rendering applies unchanged. It
  adds a nesting level to every root single read to reuse a check that is a few lines in the
  launcher.
