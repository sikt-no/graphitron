---
id: R1080
title: "A nullable root field returning one object over a row of nulls resolves to null, as v9 resolved it"
status: In Progress
bucket: bug
priority: 6
theme: codegen-correctness
depends-on: []
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
    kontaktinfo(personId: ID! @lookupKey): Kontaktinfo   # null over a row of nulls, as in v9
    hovedkontakt: Kontaktinfo!                           # always an object, as today
}
type Kontaktinfo @table(name: "PERSON") {
    epost: String @field(name: "EPOST_PRIVAT")
    telefon: String @field(name: "TELEFON_MOBIL")
}
```

| Shape | Data | Graphitron 9 | Today | After |
|---|---|---|---|---|
| `kontaktinfo(personId: 7) { epost }` | the `PERSON` row exists, both columns `null` | `null` | `{epost: null}` (see below) | `null` |
| `kontaktinfo(personId: 7) { __typename }` | as above | `null` (every field counted) | `{__typename: "Kontaktinfo"}` (see below) | `null` |
| `kontaktinfo(personId: 8) { epost }` | no `PERSON` row 8 | `null` | `null` | `null` |
| `hovedkontakt { epost }` | a row with both columns `null` | object | object | object |

The "Today" column for the `@lookupKey` rows is the anchored read's behavior; the lookup's own is
unverified, since no single root lookup is exercised anywhere today (see "The `@lookupKey` single
shape" below).

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

**The `@lookupKey` single shape, pinned first.** `LauncherCommands.lookupRow` mints
`ResultShape.RecordList(null)` for every lookup, though `GraphitronSchemaValidator.validateRootLookup`
admits a single lookup (a scalar key and a single return), so `RootLauncherRenderer.lookupBody`'s
single branch is unreachable from the producer and a single root lookup takes the list arm's
scatter. The implementer first pins today's single-lookup behavior with a pipeline case and an
execution case in the sakila example (no field there exercises it), then routes `lookupRow`'s result
through the same cardinality fork `resultShapeOf` uses, so one producer mints the single shape for
all three sources. If the pinning shows the single lookup is broken outright rather than merely
list-shaped, that fix lands here, since this item cannot reach the lookup source without it.

**The command: a sealed null check on the single result shape.** `ResultShape.SingleRecord` gains one
component, `NullCheck check`, a new sealed command type in `no.sikt.graphitron.command`:

- `NullCheck.None`: the record is returned as fetched.
- `NullCheck.Collapses(List<List<ColumnRef>> leaves, Optional<PresenceProbe> probe)`: the record is
  `null` unless some leaf has every column non-null or the probe column is set. The inner lists keep
  R1069's `Columns` leaf grouping (a composite `@nodeId` is one leaf of several columns, absent when
  any of them is `null`), which a flat column list would lose. The constructor refuses a check with
  no leaves and no probe, so "never present" is not representable and `None` is the only
  always-object form.

The shape is the right carrier for the reason `ResultShape` gives for carrying the ordering: the fact
co-varies with cardinality (a list or a connection has no single object to collapse), so a slot on
the command or on each source would make an illegal pair representable. `NullCheck` holds the
decision, not just the columns to project, which is what the launcher needs: it renders over the
command with no schema in hand. The slot means "project these and return `null` when absent" and
nothing else; R1069's batched single child, whose check runs in the field's continuation rather
than in its rows method, keeps its projection-only slots on `LaunchSource.Correlated.Projected`;
its `SingleRecord` carries `None`, and the backstop below refuses anything else there (a review
note for R1069: name those slots so they read as projection only).

**One mapping from the model's contract to the command.** A `plan/` function,
`NullChecks.of(NullContract, ...)`, maps R1069's `NullContract` to `NullCheck`
(`AlwaysObject` to `None`, `CollapsesWhenAbsent` to `Collapses`, minting the `PresenceProbe` the way
R1069's `ProjectionCommands` does). `LauncherCommands.resultShapeOf` and the R1069 fetcher path both
call it, so a child and a root turn one decision into one command shape by one conversion.
`resultShapeOf` reads the contract as `NullContract.of(qtf.returnType().wrapper(), presence)`, where
`presence` is R1069's per-type fold.

**The schema-free walk computes the same check where it knows the type.**
`LauncherCommands.produceWithoutSchema` reaches `row`, `lookupRow` and `routineRow`, and so
`resultShapeOf`, with no `GraphitronSchema`; its javadoc promises it mirrors what `produce` would
mint "matching what a schema-free assembly can classify". A schema-free assembly hands over only the
generated type's own fields, so for a root read it usually does not hold the return type's fields
at all (`TypeFetcherGeneratorTest.queryTableField_single_returnsRecord` generates `Query` with one
nullable `Film` field and no `Film` fields). An empty field list cannot stand for that: R1069's
fold over no fields is a collapse with no leaves, not "unknown". So the producers take the
presence as a lookup that can answer "not known here", a
`Function<String, Optional<...>>` from type name to R1069's per-type presence:

- The schema walk passes `typeName -> Optional.of(schema.presenceOf(typeName))`; every type is
  known.
- The schema-free walk passes `Optional.empty()` for a type absent from its `fieldsByParentType`
  map (tested with `containsKey`, not `getOrDefault`) and R1069's fold over the map's fields
  otherwise, which needs the fold stated over a field list, as `SubtreePresence.over` already is.
  If R1069 lands the fold schema-bound, this item restates it over the list.
- `resultShapeOf` mints `NullCheck.None` for an empty answer, and otherwise
  `NullChecks.of(NullContract.of(wrapper, presence), ...)`.

So the schema-free walk mints the schema walk's check for a type it was handed, and `None` for
one it was not, the same pre-existing limit `fieldsByParentType` states for the selection
restriction, now decided by membership rather than by an empty list. `NullChecks.of` never sees a
leafless `CollapsesWhenAbsent` from a known type (a GraphQL object type has at least one field,
and each field is a leaf or blocks), so it throws on one as a generator bug rather than mapping
it, which keeps "never present" out of `NullCheck`.

**A backstop: `Collapses` only where a renderer reads it.** `SingleRecord` is minted by more
producers than the three in scope: `batchedResultOf` for a batched single child (the
`Correlated.Projected` sources), `batchedPivotRow`
(`PivotAggregate`), `dmlRowOf` (`ProjectedReentry`, `DiscriminatedReentry`) and `interfaceResultOf`
(`DiscriminatedTable`). Their renderers (`BatchedRowsFragments`, `ReentryRowsFragments`,
`RootLauncherRenderer`'s discriminated arm) read the shape by `instanceof` and this plan changes
none of them, so a `Collapses` reaching one would be dropped without a word. `LauncherCommand`'s
constructor therefore gains one source-by-result backstop, stated as an allow-list so a new source
is refused until someone renders the check for it: a `SingleRecord` whose check is `Collapses`
requires a `LaunchSource.AnchorTable`, `LaunchSource.KeyedLookup` or `LaunchSource.RoutineChain`
source. Each refused family has an owner the refusal mirrors:

- The batched single child: R1069 runs its check in the field's continuation, never in the rows
  method, because one rows method can serve a `T` and a `T!` site.
- `PivotAggregate`: the opposite convention, a record of null slots and never a null record, pinned
  by `BatchedChildExecutionTest.pivotChild_eachParentGetsItsOwnRecord_rowLessParentGetsNullSlots`.
- `DiscriminatedTable` and `DiscriminatedReentry`: interface returns, R1069's exclusion, with no
  single projection alias to read leaves off. (`DiscriminatedCorrelatedChain` mints no
  `SingleRecord` today; the allow-list refuses it too, should it start.)
- `ProjectedReentry`: a mutation's payload read, out of scope by choice. Bringing it in is a
  deliberate edit to the allow-list together with a rendering in `ReentryRowsFragments.projectedBody`.

The three allowed sources are only ever minted with `Invocation.Direct`, so the allow-list needs no
invocation half.

**Projection.** In the three single arms of `RootLauncherRenderer` (the anchor arm's `selectChain`,
`lookupBody`'s single branch, and the routine arm, which shares `selectChain`), a `Collapses` check
turns the inline `$project(...)` select into a `selectFields` local, the way `lookupBody`'s list
arm already adds `__idx__`: the projection, then each leaf column read off the projected node's
alias, then the probe column, rendered by R1069's `PathFragments` probe renderer correlated on that
same alias, under a new `ReservedAliases.PRESENCE` alias. The correlated alias is the table local
for the anchor and lookup arms, and the chain's terminus alias (the connection arm's `aliasLocal`)
for the routine arm. Columns are projected whatever the query selects, so the decision is
selection-independent, as it is for children. A leaf column the projection also selects is selected
twice under one name, as `CallWrap.SameRow`'s presence columns already are beside the same-row
child's `$project`.

**Compile-graph edges.** A probe term carries a `GlueCall` filter (a condition unit, often on
another type's conditions class), and its reach hops may carry filter units. `PlanCompileGraph.addLauncherEdges`'
`case ResultShape.SingleRecord ignored -> { }` arm becomes a walk over the check's probe, adding a
`precise(owner, ...)` edge to each unit it names, so an incremental compile recompiles the launcher
when a probe's condition changes. The switch is already exhaustive, so nothing forces this edit;
the plan names it so it is not missed. (R1069's `CallWrap` and `Correlated.Projected` slots have
the same gap; flagged to its reviewer.)

**The check lives in the launcher.** After `fetchOne()`, the arm binds the record, returns `null`
when it is `null` (no row, as today), and otherwise renders the check: present when some leaf has
every column non-null or the probe column is set, else `null`. The rendering is R1069's
`FetcherEmitter.presenceCheck`, lifted into a `render` fragment (`PresenceFragments.check`) that
takes a `NullCheck.Collapses`, which both `FetcherEmitter` and `RootLauncherRenderer` call, so a
child and a root read decide presence with one emitter over one input. It renders plain `if`
statements, since generated code targets Java 17. The launcher is the place because the entry point
is thin by design: `LauncherCommand` leaves it nothing to say beyond acquiring a connection and
wrapping the payload, and keeping the presence alias's writer and reader in one method keeps the
pair local. `RootLauncherRenderer.valueTypeOf` and `TypeFetcherGenerator.buildQueryTableFetcher`
stay unchanged: the launcher still returns a `Record` or `null`, now with `null` meaning "nothing to
show" rather than only "no row". A launcher serves one coordinate, so unlike R1069's shared batched
rows method there is no `T` and `T!` site to keep apart. The fanned tenancy needs nothing:
`LauncherCommand` already restricts it to `RecordList`.

**Docs.** R1069's manual section "When a single object resolves to `null`" in
`docs/manual/how-to/map-types-to-tables.adoc` is reworded from "a nullable field of a `@table`
type" to "a nullable field returning one `@table` object, on a table type or on `Query`", and its
closing paragraph narrows to "A field returning an interface or union, a `@service` field and a
mutation's payload return what they fetch, so a row of nulls there is an object." In
`docs/manual/how-to/migrating-from-legacy.adoc`, R1069's bullet "A field of `Query` returning one
table object stays an object over a row of nulls, where Graphitron 9 returned `null`" is deleted.
`ResultShape`'s class javadoc ("one record or null") and `SingleRecord`'s say that `null` now also
means "nothing to show", and name the check.

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
  `TableChildNullContractPipelineTest`): today's single `@lookupKey` root shape, pinned before it
  changes, then the `SingleRecord` it gets after; the `NullCheck.Collapses` a nullable
  `QueryTableField` gets over each source (anchor, `@lookupKey`, `@routine` chain) carries the
  type's leaves with their grouping (a composite `@nodeId` as one leaf) and, for a type holding a
  reference, a probe; a non-null field gets `None`, and a list and a connection have no
  `SingleRecord`; a `QueryTableInterfaceField`, a DML payload read and a batched single child get
  `None`. The schema-free walk (`produceWithoutSchema`): an assembly handing over `Query` and the
  return type's fields mints the same check as the schema walk; one handing over `Query` alone
  mints `None` (the `queryTableField_single_returnsRecord` shape, which stays green). The
  `LauncherCommand` backstop rejects `Collapses` on a `CorrelatedChain`, a `PivotAggregate`, a
  `ProjectedReentry`, a `DiscriminatedReentry` and a `DiscriminatedTable` source, and admits it on
  the three in-scope sources; `NullCheck.Collapses` refuses an empty check, and `NullChecks.of`
  throws on a leafless contract. Assertions are on the commands, not on generated method bodies.
- **Compile graph** (`PlanCompileGraphTest`): a nullable single root over a type whose probe carries
  a condition gets a precise edge from the launcher owner to that condition's unit.
- **Execution** (`graphitron-sakila-example`, over R1069's fixture table whose rows exist with every
  mapped column `null`, through new `Query` fields):
  - today's single `@lookupKey` root read, pinned first (no sakila field exercises it yet);
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

- `ResultShape.SingleRecord()`, the nullary form (the record gains its `NullCheck`; the name stays).
- `FetcherEmitter.presenceCheck` (lifted to `PresenceFragments.check`).
- In the manual: "A field of `Query` ... return what they fetch" for a `Query` field returning one
  table object.

## Other solutions we've considered

- **The check in the entry point** (`buildQueryTableFetcher`), on `payload`. It would leave the
  launcher untouched, but the entry point is thin by design, owning connection acquisition and
  nothing about the statement, and the presence alias's writer would sit in one method and its
  reader in another.
- **Two flat slots on `SingleRecord`** (`List<ColumnRef>` and `Optional<PresenceProbe>`, both empty
  for "always an object"), shared with R1069's batched child. A flat list loses the leaf grouping a
  composite `@nodeId` needs; "both empty" stands in for a decision rather than stating it; and the
  shared slot would mean "check here" at the root and "only project" at a batched source.
- **Slots on each in-scope `LaunchSource`** (`AnchorTable`, `KeyedLookup`, `RoutineChain`), as
  R1069 does for `Correlated.Projected`. Three records gain the same two components and each list
  arm must pass empty ones; the fact belongs to cardinality, not to where the rows come from.
- **Wrapping the root in a `Multiset` of one row**, so the child rendering applies unchanged. It
  adds a nesting level to every root single read to reuse a check that is a few lines in the
  launcher.
- **Mapping a leafless contract to `None` in `NullChecks.of`**, leaving the schema-free walk's
  `getOrDefault(..., List.of())` as it is. Smaller, but it reads an empty field list as "unknown"
  at the one site that cannot tell, and lets a real generator bug (a known type yielding no leaves)
  pass as "always an object". Deciding by map membership states what the walk actually knows.
- **Refusing `Collapses` per excluded source** rather than allowing it on three. A new source
  minting `SingleRecord` would then admit a check its renderer drops; the allow-list refuses it
  until someone renders the check there.

## Reviewer findings

### Round 1: Spec → Ready, request revisions (session_01FU47nA3ZZmzh8B2VwDdNqN, 2026-10-21)

Question 1 passes. When this lands, a nullable `Query` field returning one `@table` object (plain,
`@lookupKey` or `@routine`) gives `null` instead of an object of nulls when the row it found holds
nothing under R1069's rule, which is what Graphitron 9 gave; a non-null field and a missing row
behave as today. That is reachable: every symbol, test and branch the plan names exists as named
(or is R1069's, which is Ready), `lookupRow` does mint `RecordList(null)` for every lookup while
`validateRootLookup` admits a scalar key with a single return, and the Graphitron 9 citations hold
on `graphitron-9-head`. Two findings under question 2 hold it in Spec; each is a claim about
existing code that does not hold, and each leaves the implementer a design call.

1. **The schema-free walk does not mint `None` for a type it was never handed; it mints a
   collapse with no leaves.** The plan states the fold over `fieldsOfType` and says the walk's
   `fieldsByParentType` "mints `None` only for a type whose fields the assembly never handed over".
   That lookup answers `List.of()` for such a type, and `NullContract.of` over an empty field list
   yields `CollapsesWhenAbsent` with no leaves (`SubtreePresence.over([])` is `Leaves([])`); R1069
   keeps `NullContract.of`'s shape. `NullChecks.of` would then build a `NullCheck.Collapses` with
   no leaves and no probe, which this plan's constructor refuses. A schema-free assembly hands over
   only the generated type's own fields, so this is the common case, not an edge:
   `TypeFetcherGeneratorTest.queryTableField_single_returnsRecord` generates `Query` with one
   nullable single `Film` field and no `Film` fields, and would throw. Satisfied when the plan says
   how the walk tells "type this assembly does not know" from "type with these fields" (for
   example a lookup answering `Optional`, or `NullChecks.of` mapping a leafless contract to `None`
   with the reason stated, which also decides what a leafless contract means on the schema path),
   and the pipeline test for `produceWithoutSchema` covers the unhanded type.

   *Author, round 1:* the producers now take presence as a lookup answering
   `Optional`: the schema walk always answers, the schema-free walk answers only for a type in
   its `fieldsByParentType` map (by `containsKey`), and `resultShapeOf` mints `None` for an
   empty answer. `NullChecks.of` throws on a leafless contract as a generator bug, since a known
   type cannot yield one. The tests cover both assemblies (with and without the return type's
   fields), and `queryTableField_single_returnsRecord` stays green. "Other solutions" records why
   the leafless-to-`None` mapping was not taken.

2. **The backstop list misses the arms that mint `SingleRecord` but would not render the check.**
   The plan puts `NullCheck` on `ResultShape.SingleRecord` because a slot elsewhere "would make an
   illegal pair representable", but `SingleRecord` is also minted for `Invocation.Batched` over the
   `Correlated.Projected` sources (`batchedResultOf` when `emitsSingleRecordPerKey()`) and for
   `Invocation.ReturningKeyed` over `ProjectedReentry` and `DiscriminatedReentry`.
   `BatchedRowsFragments.body` and `ReentryRowsFragments` read the shape by `instanceof`, and the
   plan changes neither, so a `Collapses` reaching them is dropped without a word. The batched
   case is the one R1069 says must not check in the rows method (a rows method serving a `T` and a
   `T!` site), and the plan backstops `PivotAggregate` and the discriminated arms but not it. The
   `ProjectedReentry` paragraph says a check there "renders fine", but nothing in the plan renders
   it in `ReentryRowsFragments.projectedBody`. Satisfied when the constructor admits `Collapses`
   only where a renderer reads it (for example only with `Invocation.Direct`, or only on the three
   in-scope sources) or the plan says why each remaining pair is safe, the `ProjectedReentry`
   sentence matches, and the backstop tests name the sources added.

   *Author, round 1:* the backstop is now an allow-list: a `SingleRecord` whose check is
   `Collapses` requires `AnchorTable`, `KeyedLookup` or `RoutineChain`. The section names every
   other producer of `SingleRecord` and the owner each refusal mirrors (R1069's continuation-side
   check for the batched child, the pivot convention, the interface exclusion, and this item's
   scope for `ProjectedReentry`). The "renders fine" sentence is gone: bringing mutation payloads in
   is an allow-list edit plus a rendering in `ReentryRowsFragments.projectedBody`. The backstop
   tests name `CorrelatedChain`, `PivotAggregate`, `ProjectedReentry`, `DiscriminatedReentry` and
   `DiscriminatedTable`.

Non-blocking: Graphitron 9's `createMappingFunction` lives in `FetchDBMethodGenerator`, beside
`generateSelectRow`, and it dropped `nullOnAllNull` for a non-null field only when the type had a
nullable field and no ID field; neither changes the plan.
