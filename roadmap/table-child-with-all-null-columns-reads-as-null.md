---
id: R1069
title: "A single-row child whose projected values are all null resolves to null, as v9 resolved it"
status: Ready
bucket: bug
priority: 4
theme: codegen-correctness
depends-on: []
created: 2026-10-19
last-updated: 2026-10-21
---

# A single-row child whose projected values are all null resolves to null, as v9 resolved it

## Goal

A nullable field of a `@table` type, or of an object nested in one, that returns one object
resolves to `null` when that object would hold nothing: every column it reads is `null`, every
single reference in it finds no value, and every list in it is empty. This is how Graphitron 9
resolved it, so a client that reads `postadresse != null` as "has an address" keeps working across
the upgrade. The rule is the same for a child with its own table (inline or `@splitQuery`), for a
same-table child reading its parent's row, and for a nested object without a table, and it is
decided over every field of the type, whatever the query selects. A field declared non-null always
resolves to an object, as today.

The rule covers those four child kinds (`ChildField.NestingField`, `ChildField.SameRowTableField`,
`ChildField.TableField` and `ChildField.BatchedTableField`, single-valued). It does not reach a root
field, a single field returning an interface or union, or a `@service` field: they return what they
fetch, as today. Graphitron 9 collapsed a root `@table` object too; that divergence is listed in the
migration guide below and its fix is R1080, which builds on the per-type rule this item introduces.

Three shapes from sis, the consumer whose Graphitron 9 against Graphitron 10 differential test
found this (2026-10-19), all selected by production operations:

```graphql
type PersonProfil @table(name: "PERSON") {
    postadresse: Postadresse @splitQuery            # a child with its own table
}
type Postadresse @table(name: "STUDENT") {
    # four fields over nullable *_SEMADR columns of STUDENT
}

type Termin @table(name: "TERMIN") {
    studiesemesterPeriode: StudiesemesterPeriode    # a nested object without a table
}
type StudiesemesterPeriode {
    fraDato: Date @field(name: "DATO_START_SEMESTER")
        @reference(path: {table: "SEMESTERREGISTRERINGSTERMIN"})
    tilDato: Date   # the same shape, over another column of the same table
}

type ProgramStudierett @table(name: "PROGRAMSTUDIERETT") {
    phd: ProgramStudierettPhdData                   # a nested object holding a list
}
```

| Shape | Data | Graphitron 9 | Today | After |
|---|---|---|---|---|
| `postadresse { __typename }` | the `STUDENT` row exists, all four columns `null` | `null` | `{__typename: "Postadresse"}` | `null` |
| `studiesemesterPeriode { fraDato tilDato }` | `TERMIN`'s join column is set, no `SEMESTERREGISTRERINGSTERMIN` row matches | `null` | `{fraDato: null, tilDato: null}` | `null` |
| `phd { beregnetSluttdato }` | a study right that is not a PhD: column `null`, references find nothing, list empty | `null` | `{beregnetSluttdato: null}` | `null` |

Measured in sis's local data: `postadresse` takes the object form for 1543 of 2048 students, and
`studiesemesterPeriode` differs on 46 of 50 rows of a production operation's shape.

## The decision: absence is judged by value, and nullability is the declaration

The Backlog question was whether "absent" is judged by the join (the row exists, so the object
exists: today's reading) or by the values (Graphitron 9's `nullOnAllNull`, which collapsed a row of
nulls to `null`), and whether that should be a default or a declared choice per field.

Absence is judged by value, by default, for every nullable single-row object. The reasons:

- **Graphitron 9 did it everywhere.** Its generator wrapped every object row, with or without
  `@table`, inline or `@splitQuery`, in `Functions.nullOnAllNull` (`FetchDBMethodGenerator.createMapping`
  on the `graphitron-9-head` branch), built from every field of the type rather than the selection.
  Every migrating consumer was written against that reading; a per-field opt-in would make each of
  them find and annotate every such field to get their old answers back. This item restores the
  reading for the four child kinds named in the Goal, where every observed consumer shape sits; the
  same `generateSelectRow` also wrapped a root object's row, which R1080 takes on.
- **The schema already has the declaration.** A non-null field (`postadresse: Postadresse!`) always
  resolves to an object; a nullable one may resolve to `null`. An author who wants "the row exists"
  to mean "an object" declares the field non-null. No new directive is needed, and none is added.
  What nullability cannot say is "null when no row exists, an object when a row of nulls exists":
  a non-null `@table` child with no matching row is an error, as today. No observed consumer shape
  wants that third reading, since every one wants Graphitron 9's answer; a consumer that does is a
  fresh Backlog item for a directive, which this rule leaves room for.
- **It conflates "no row" with "a row with no values", and that is the point.** To a client both
  are "nothing to show". A nested object without a table has no row of its own to be present,
  which is why R1026 already chose the value reading there; extending the same reading to a child with a
  table gives one rule rather than two.

The rule stays selection-independent, as R1026 made it: `postadresse { __typename }` and
a selection of its columns agree on whether `postadresse` is `null`. That is where it differs from
Graphitron 9, which skipped unselected subquery fields under its `optionalSelect` setting and then
counted them as `null`. The one place this shows is a list, covered below.

## The presence rule

A **presence leaf** is one value an object carries that can prove the object non-empty. An object
is absent when every leaf is absent; a nullable single field over an absent object resolves to
`null`. The leaves of a type, decided over every field of the type:

| Field in the type | Leaf | Absent when |
|---|---|---|
| A column (`@field`), a `@nodeId` | own-row columns | the column is `null` (for `@nodeId`, any key column is `null`) |
| A nested object without a table | its own leaves, merged | (recursive) |
| A single `@reference`, scalar or object, keyed or `condition:`-joined, inline or `@splitQuery` | a probe | no referenced row has a value (below) |
| A list of a `@table` type taking no arguments, inline or `@splitQuery` | a probe | the list is empty |
| A list taking arguments, a connection, an interface or union, an `@externalField`, a `@service` field, a `@pivot` child, a same-table child | none: blocks the collapse | (the object always resolves to an object) |
| A reference or list whose path runs through a database routine (a lateral hop), or whose filters bind anything but request context | none: blocks the collapse | (as above) |
| Every other field (a computed field, a record read, a pivot slot and the like) | none: blocks the collapse, through `SubtreePresence.over`'s `default` arm as today | (as above) |

A **probe** is a correlated `EXISTS` over the field's own join path and filters, the ones its fetch
already applies, so a probe can never disagree with the value the field would resolve to. For a
single object reference the probe asks for a referenced row in which some column leaf of the target
type is non-null; when the target type has no column leaves, or holds probes or blockers of its
own, the row's existence alone counts. That last step errs toward keeping an object, never toward
dropping a value, and it stops the rule from recursing through the schema.

Two limits keep a probe renderable in a statement that may not select the field. Its path must be
a `ReachPath`, the condition rail's join-path type, which refuses a lateral routine hop; a field
reached through a routine blocks instead. And its filters must not read the field's own selection:
a field for which `ProjectionCommands.readsSelectedFieldArguments` holds blocks, because an
unselected field has no arguments to serve. An argument-free field's `@condition` that binds only
request context passes, since the projection's `env` serves it.

What changes against today's rule (`ChildField.NestingField.SubtreePresence.over`):

- A single reference now counts by its value, not by its first hop's join columns. Today a set join
  column whose target row is missing keeps the object (the `studiesemesterPeriode` shape); a probe
  answers it. A `condition:`-joined reference, which blocked, now probes too; one reached through
  a database routine still blocks.
- A list taking no arguments, and a `@splitQuery` field, no longer block: they probe. A list
  counts as absent when it is empty. This is a deliberate rule rather than a restoration:
  Graphitron 9 gave a selected empty list as `[]`, which kept the object, and an unselected one as
  `null`, which did not. "Empty counts as nothing" is the selection-independent reading that agrees
  with Graphitron 9 wherever the list was not selected, which is the `phd` shape. A list with
  arguments still blocks, because its emptiness depends on the query.
- `@splitQuery` is a delivery choice and does not change the answer: a `@splitQuery` reference or
  list inside an object probes exactly as its inline form does. The probe runs in the parent's
  statement, guarded as below. This departs from Graphitron 9 in the one direction a client
  notices: Graphitron 9's `generateSelectRow` put each resolver field's key row
  (`getKeySetForResolverFields`) among the values `nullOnAllNull` saw, so a type holding a
  `@splitQuery` field kept its object whenever that key was set, even when the split list was
  empty or the split reference found no row. Here such an object resolves to `null`. The migration
  guide lists it.
- A child with its own table now has a contract at all. Today it is `null` only when no row matches
  (or, for `@splitQuery`, when its key is `null`).

`@pivot` children keep their opposite convention (a record of null slots, never a null record,
pinned by `BatchedChildExecutionTest.pivotChild_eachParentGetsItsOwnRecord_rowLessParentGetsNullSlots`).
A non-null field keeps `AlwaysObject`.

A same-table child (a field returning a `@table` type bound to its parent's own table, which reads
the parent's own row) blocks the collapse when it sits *inside* an object, as today, because
following it would re-enter the same row and recurse. When the same-table child *is* the nullable
field, it is a single object like any other: its type's leaves, under this rule, decide it, read
from the parent's row. That is the sis `Karakter.navnAlleSprak` shape, which returns
`{nb: null, en: null}` for a grade with no name where Graphitron 9 returned `null`. Graphitron 9
counted every field of the type, so every field was `null` there, and today's rule
(`GraphitronSchema.sameRowNullContract`) only keeps an all-null type as an object when one of its
fields blocks: a reference, a list or a `@splitQuery` field, which this rule turns into probes, or
one of the blockers it keeps. The implementer pins the shape first, with `navnAlleSprak`'s return
type taken from sis as a fixture (relayed through the sis session when the implementer has no sis
checkout), and reports which field blocks. When it is a probe kind, this plan
fixes it. When it is a kept blocker, the item names that divergence in the migration guide. When no
field blocks, the fault is in how the same-row presence check is generated, and it is fixed here,
since the same check then serves the `@table` child.

A type with a `@nodeId` field, or any column that is never `null` on an existing row such as its
primary key mapped to a field, never collapses while its row exists. That matches Graphitron 9,
whose `nullOnAllNull` saw the same non-null value.

## User documentation (first-client check)

`docs/manual/how-to/map-types-to-tables.adoc` gains a short section, "When a single object resolves
to `null`", which the "Grouping columns into a nested object" and "Returning the parent's own row"
sections then point at instead of stating their own rule:

> A nullable field of a `@table` type, or of an object nested in one, that returns one object
> resolves to `null` when the object would hold nothing: every column it reads is `null`, every
> single `@reference` in it finds no value, and every list in it is empty. A child with its own
> `@table` also resolves to `null` when no row matches. This is
> decided over all of the type's fields, whatever the query selects, so `address { __typename }`
> and `address { street }` agree on whether `address` is `null`. `@splitQuery` does not change the
> answer.
>
> ```graphql
> type Customer @table {
>     address: Address @splitQuery   # null when the row is missing or every column of it is null
>     billing: Address!              # always an object; an error if no row matches
> }
> ```
>
> Declare the field non-null to always get an object. A type holding a list that takes arguments, a
> connection, an interface or union, an `@externalField`, a `@service` field, or a field reached
> through a database routine always resolves to an object, because whether it holds anything
> depends on the query or on code outside the table's rows. A type with a `@nodeId` field, or a
> field reading a column that is never `null`, never resolves to `null` while its row exists.
>
> The rule is for fields of table types. A field of `Query`, a field returning an interface or
> union, and a `@service` field return what they fetch, so a row of nulls there is an object.

`docs/manual/how-to/migrating-from-legacy.adoc` replaces the "Behavior divergence: nested objects
holding a fetched field" section and the matching "Things that didn't change" bullet:

> A nullable field of a table type returning one object resolves to `null` when the object would
> hold nothing, as in Graphitron 9, for a nested object without `@table` and for a child with its
> own table alike; see [Map types to tables] under "When a single object resolves to `null`". These
> cases differ from Graphitron 9:
>
> - Graphitron 9 decided from the fields it fetched, so with `optionalSelect` an unselected list or
>   reference counted as `null`, while a selected empty list kept the object. The rewrite decides
>   over every field whatever the query selects, and counts an empty list as nothing.
> - Graphitron 9 counted a `@splitQuery` field by the key it batched on, so an object holding one
>   stayed an object whenever that key was set. The rewrite counts it by its value, the same as its
>   inline form: an empty split list or a split reference that finds no row counts as nothing.
> - A type holding a list that takes arguments, a connection, an interface or union, an
>   `@externalField`, a `@service` field or a field reached through a database routine always
>   resolves to an object.
> - A field of `Query` returning one table object stays an object over a row of nulls, where
>   Graphitron 9 returned `null`.
>
> A client that tested such a field for `null` should test the fields inside it instead.

The docs state the rule once and name its exceptions in one list, which is the check this draft
exists to pass. The last bullet goes when R1080 lands.

## Implementation

**Model: one presence rule, lifted out of `NestingField`.** `PresenceLeaf`, `SubtreePresence` and
`NullContract` move from `ChildField.NestingField` to a top-level home in `rewrite.model` (a
`Presence` file holding the three), since they now serve three field kinds. `PresenceLeaf` becomes
sealed, because its kinds carry different data and evaluate differently:

- `Columns(List<ColumnRef>)`: own-row columns, absent when any is `null`.
- `Probe(ChildField field, List<JoinStep> joinPath, List<WhereFilter> filters, List<ColumnRef> targetColumns)`:
  the path and filters the field's own fetch is built from, read off the field (a
  `TableTargetField`'s `joinPath()` and `filters()`, a `ColumnBackedReferenceField`'s `joinPath()`
  with no filters), and the target columns whose non-null value proves the reference present: the
  scalar reference's own column, an object reference's target column leaves, or empty when row
  existence alone counts (a list, or a target type with no column leaves or with probes or
  blockers of its own). The constructor refuses a path with a lateral hop, the same refusal
  `ReachPath` makes, so a blocked shape never reaches the renderer.

`SubtreePresence.over` switches over the field kinds per the table above. `NullContract.of` keeps its
shape; `CollapsesWhenAbsent` holds the sealed leaves and exposes `presenceColumns()` (the `Columns`
leaves) and `probes()` separately. `NestingField.referenceLeaf` is retired.

**Where the contract is computed.** A nested object's leaves depend on the edge, since its fields
are classified against the host's table, so `NestingField.nullContract()` stays a method on the
field. A child with its own table has leaves that depend on its return type alone, so they are one
fold on the schema keyed by type: `GraphitronSchema.sameRowNullContract` generalises to a
`presenceOf(typeName)` fold, and the contract at a use site is `NullContract.of(wrapper, presence)`.
`TableField`, `BatchedTableField` and `SameRowTableField` gain no presence component. The projection
side (`plan/ProjectionCommands`) and the fetcher side (`FetcherEmitter`) both read the fold, as
`SameRowTableField` already does.

**The probe as a command.** A probe is a rendered expression under a derived alias, not a column of
the row, so it does not ride the column vocabulary. `ParentRowDemand.parentRowColumns()` stays what
it is, the base-name columns a fetcher reads off the parent row, and the parent-projection walks
that enforce it and its other implementers (the polymorphic children, `TableInterfaceField`) are
untouched. A new command record in `no.sikt.graphitron.command`, `PresenceProbe(String alias,
List<ProbeTerm> terms)`, carries the probe column, with `ProbeTerm(ReachPath reach, GlueCall filter,
List<ColumnRef> targetColumns)` for each `Probe` leaf. `ProjectionCommands` mints it from the
contract: the `ReachPath` from the leaf's `joinPath`, the `GlueCall` through the same `glueFor` the
`Multiset` arm's `filter` comes from, or `null` when the field has no filters. Each carrier gains it
beside its column list, as an `Optional<PresenceProbe>`:

- `CallWrap.Splice(List<ColumnRef> callerDemand, Optional<PresenceProbe> probe)`, filled from the
  nesting field's contract where `callerDemand` is filled from `parentRowColumns()` today.
- `CallWrap.SameRow(List<ColumnRef> presence, Optional<PresenceProbe> probe)`.
- `CallWrap.Multiset` gains `List<ColumnRef> presence` and `Optional<PresenceProbe> probe`, both
  empty unless `arity` is `SINGLE`; the constructor refuses them on a `LIST` arity, as it refuses
  `limitByFirstArgument` on a `SINGLE` one.
- The two `LaunchSource.Correlated.Projected` records, `CorrelatedChain` and
  `CorrelatedLookupChain`, the batched rows method's source for a `@splitQuery` single child, gain
  the same two components, read by `BatchedRowsFragments.body` where it builds `selectFields`.

**Rendering a probe.** `ConditionGlueRenderer.reachExists` already renders a correlated `EXISTS` over
a `ReachPath`, through `PathFragments`' hop helpers, for the FK-target `@condition`. It moves to
`PathFragments` beside those helpers and takes the correlated alias as a parameter (today it is
hard-wired to the projection's `table` local), so the condition rail and the probe call the one
renderer. The inner condition of a probe term is the target columns' `isNotNull()` disjunction, or
`DSL.noCondition()` when they are empty, conjoined with the filter's glue call against the terminal
alias. The correlated alias is the unit's `table` local in the `Splice` and `SameRow` arms, and the
child's terminal alias inside the `Multiset` arm and the batched rows method, where the probe sits in
the child's own select list.

**Projection.** Per collapsing field, its `Columns` leaves are projected, plus one probe column when
it has probes, aliased so its fetcher can name it statically:

```sql
CASE WHEN c1 IS NULL AND c2 IS NULL
      AND (EXISTS (...probe 1...) OR EXISTS (...probe 2...)) THEN 1 END
```

rendered through jOOQ's `DSL.when(...)` so it is dialect-neutral (no boolean select column, which
Oracle before 23 lacks). The guard keeps the probes from running when a column already proves the
object present. Where each lands:

- A nested object without a table: the columns as today, through `ParentRowDemand` and
  `CallWrap.Splice.callerDemand`; the probe through `Splice.probe`, rendered in the same arm. The
  alias is derived from the nested type and field name, which is what a shared nested type's
  single fetcher can read.
- A same-table child: `CallWrap.SameRow`'s presence columns as today, its probe inside the same
  single-row multiset.
- An inline single `@table` child (`TableField`): `CallWrap.Multiset`'s new presence slots,
  rendered inside the correlated single-row multiset beside `<Child>.$project(...)`, the way
  `sameRowArmBody` renders `SameRow`'s.
- A `@splitQuery` single `@table` child (`BatchedTableField`, `emitsSingleRecordPerKey()`): the
  `Correlated.Projected` slots, added to the rows method's `selectFields` in `BatchedRowsFragments`.

**Fetchers.** `FetcherEmitter.presenceCheck` grows to read the probe column besides the leaf columns
and stays plain `if` statements, since generated code targets Java 17:

- `nestingBody` and `sameRowBody`: unchanged apart from the probe.
- The single `TableField` arm of `FetcherEmitter.bindRaw`: after `r.get(0)`, run the check and return
  `null` when the object is absent.
- The `@splitQuery` single fetcher: the check runs in that field's own continuation (beside
  `TypeFetcherGenerator.asyncWrapTail`), reading the field's contract, not in the rows method or the
  shared `scatterSingleByIdx`, so a loader or rows method serving both a `T` and a `T!` site cannot
  collapse the non-null one.

**Validator.** `GraphitronSchemaValidator.comparePresenceColumns` extends to probes: a nested type
shared across parents must agree on its probes as it agrees on its columns, since one fetcher reads
one probe alias for every site.

**Docs.** The two manual edits drafted above; the `NullContract.Reason.BlockedBy` javadoc and the
`SameRowTableField` javadoc follow the new rule.

## Cost

A nullable single `@table` child now projects its own `Columns` leaves always, a few columns in a
statement that already reads its row. Probes are the real cost, and the guard is weaker than it
looks: it saves the `EXISTS` only when a column leaf is non-null, and a type made only of references
(`studiesemesterPeriode`) has no column to guard with, so every selection of it pays one `EXISTS` per
reference leaf, each a keyed lookup on the referenced table. Where the leaf is also selected, its
value is computed twice. That is the price of deciding over the whole type, and it buys the answer
Graphitron 9 gave, which also computed every reference of the object. Nothing in the build measures
this; the reviewer checks it against the generated SQL in the sakila example, and if a consumer
measures it as a problem, deduplicating a probe against a selected leaf is a follow-up item.

## Tests

- **Model** (`NestingFieldNullContractTest`, moving with the types): the leaf kind each field kind
  yields, per the table, including a `@splitQuery` reference and an argument-free list as `Probe`, a
  list with arguments and a connection as blockers, an object-reference probe whose target has
  non-column leaves counting row existence alone, a `condition:`-joined reference as `Probe`, and a
  field reached through a routine hop or whose filters read its selection as blockers.
- **Pipeline** (`NestingFieldPipelineTest`, `SameTableInlineChildPipelineTest`, and a new
  `TableChildNullContractPipelineTest`): the contract for an inline and a `@splitQuery` single
  `@table` child, a non-null one as `AlwaysObject`, and a shared nested type with differing probes
  rejected; the `PresenceProbe` each carrier (`Splice`, `SameRow`, `Multiset`,
  `Correlated.Projected`) receives, and none on a list-arity `Multiset`. Assertions are on the model
  and the commands, not on generated method bodies.
- **Execution** (`graphitron-sakila-example`, beside `NestingNullContractExecutionTest`), over a new
  fixture table whose rows exist with every mapped column `null`:
  - an inline and a `@splitQuery` single `@table` child with an all-null row resolves to `null`; one
    non-null column keeps the object; a missing row is `null` as today;
  - `{ __typename }` agrees with the full selection, for both;
  - a child type with a `@nodeId` field never collapses while its row exists;
  - a non-null field over an all-null row is an object;
  - a nested object of references whose join column is set but whose target row is missing resolves
    to `null` (the `studiesemesterPeriode` shape);
  - a nested object holding an argument-free list resolves to `null` when its columns are `null` and
    the list is empty, and to an object when the list is not empty (the `phd` shape);
  - a nested object holding a `@splitQuery` list, whose key is set and whose list is empty,
    resolves to `null` exactly as its inline form does (the Graphitron 9 divergence the migration
    guide lists);
  - the `@splitQuery` collapse adds no statement (counted, as existing batching tests count them);
  - the FK-target `@condition` tests stay green across `reachExists`' move to `PathFragments`;
  - a nullable same-table child shaped like `Karakter.navnAlleSprak`, over a row whose columns it
    reads are all `null`, resolves to `null` (beside `SameRowTableChildExecutionTest`).
- The existing `NestingNullContractExecutionTest`, `SameTableInlineChildPipelineTest` and
  `GraphQLQueryTest.splitTableField_singleCardinality_*` cases stay green unchanged; the R1026
  divergence assertions that a list or `@splitQuery` field keeps the object are rewritten to the new
  rule.

## Retired vocabulary

- `ChildField.NestingField.PresenceLeaf`, `ChildField.NestingField.SubtreePresence`,
  `ChildField.NestingField.NullContract` (moved, not deleted; the nested names go)
- `ChildField.NestingField.referenceLeaf`
- `GraphitronSchema.sameRowNullContract` (generalised to the per-type fold)
- `ConditionGlueRenderer.reachExists` (moved to `PathFragments`, the name may stay)
- In the manual: "always resolves to an object" for a nested type holding a list, a `@splitQuery`
  field or a `condition:`-joined reference; "counts as present whenever its join columns are set"

## Other solutions we've considered

- **A directive declaring the collapse per field.** Rejected: nullability already declares it, and
  an opt-in would make every migrating consumer re-annotate every field to recover Graphitron 9's
  answers.
- **Judging a single reference by its join columns, as R1026 does.** Rejected: it cannot see a
  missing target row, which is the `studiesemesterPeriode` shape a production operation selects.
- **Counting a `@splitQuery` field by its key columns, as Graphitron 9's split key row effectively
  did.** Cheaper, since it adds no subquery to the statement the author split the field out of, but
  it makes `@splitQuery` change the answer rather than only the cost. The guarded probe keeps the two
  forms equal.
- **Judging by the projected values only.** Rejected: the projection depends on the selection (a
  `{ __typename }`-only selection projects a sentinel and no column), so the answer would too.

## Consumer input (2026-10-20)

Relayed from the sis session as the user's decision on the Backlog question, recorded here for the
Spec review: collapse by value by default (this plan's choice), with a per-field way to keep "exists
but empty" distinguishable (this plan answers that with a non-null declaration rather than a
directive; the reviewer should confirm that suffices), and the list-holding wrapper (`phd`)
collapsing too unless the plan finds a reason not to.

One further case from sis's retest on trunk after R1045 landed: `Karakter.navnAlleSprak`, a
same-table child that R1045 now reads from the parent's row, returns `{nb: null, en: null}` for a
grade with no name where Graphitron 9 returns `null`. The plan's table lists a same-table child as
blocking the collapse when it sits *inside* an object; it should also say what happens when the
same-table child *is* the nullable object, which is this shape.

The plan answers this input in place: the non-null declaration and its one gap under "The decision",
the `phd` list under "The presence rule", and `navnAlleSprak` in the paragraph on same-table
children there.

## Reviewer findings

### Round 1: Spec → Ready, request revisions (session_01Gj4VEXhM5741X9PgponE9e, 2026-10-20)

The decision (absence by value, nullability as the declaration) is sound, the model extends the
`NullContract` / `SubtreePresence` shape and the `sameRowNullContract` per-type fold already in the
tree, and every symbol, test and manual section the plan names exists as named. Two findings hold it
in Spec.

1. **Question 1: the promise is wider than the plan, and the divergence list is incomplete.** The
   Goal's first sentence, "The decision" (whose lead argument is that Graphitron 9 did it
   *everywhere*) and the drafted manual text ("A nullable field that returns one object resolves to
   `null` when ...") state the rule for every nullable single-object field. The plan reaches four
   field kinds: `NestingField`, `SameRowTableField`, `TableField` and `BatchedTableField`. Graphitron
   9's `FetchDBMethodGenerator.generateSelectRow` wraps the root object's row in the same
   `nullOnAllNull`, so a nullable root `QueryField.QueryTableField` over a row of nulls is `null`
   there and stays an object here; the single polymorphic children (`TableInterfaceField`,
   `InterfaceField`, `UnionField`) and `ServiceTableField` are likewise outside the plan. Separately,
   `generateSelectRow` puts the key row of every resolver field (`getKeySetForResolverFields`) into
   the mapped elements, so in Graphitron 9 a type holding a `@splitQuery` field kept its object
   whenever that key was set; the probe collapses it when the split list is empty or the split
   reference finds no row. The "Other solutions" section knows this, but the migration guide's "Two
   cases differ" does not list it, and it is a divergence toward `null`, the direction a client
   notices. Satisfied when the Goal and both manual drafts state the field kinds the rule covers
   (or the plan brings root single fields in), and the migration draft lists every reading that
   differs from Graphitron 9, the `@splitQuery` key case among them.

   *Author, round 1:* scoped rather than widened. The Goal now names the four child kinds the rule
   covers and the field kinds it does not reach (root, single interface or union, `@service`); the
   decision's "everywhere" bullet says the same, and the root case is filed as R1080, which depends
   on this item's per-type fold. Both manual drafts state the scope, and the migration draft lists
   four differences, the `@splitQuery` key case and the root field among them. "The presence rule"
   carries the `@splitQuery` key departure where the rule is stated.

2. **Question 2: the plan does not say what carries a probe through the projection contract.** It
   says the probe column is "added to the demand" through `ParentRowDemand` and
   `CallWrap.Splice.callerDemand`, and that the new `CallWrap.Multiset` slot works "as
   `CallWrap.SameRow` carries its presence columns". All three are `List<ColumnRef>`, and
   `ParentRowDemand` is documented as columns read off the parent row *by base name*, a contract the
   parent-projection walks enforce for every implementer (the polymorphic fields and
   `TableInterfaceField` among them). A probe is a rendered expression with a derived alias, not a
   column of the row, so this is the one structural change to the projection side and the
   implementer would be designing it. Satisfied when the plan names the shape: for example one
   presence-term type (column or probe) that `ParentRowDemand`, `Splice`, `SameRow` and the
   `Multiset` slot all carry, or a probe demand kept beside the column demand, and says which walks
   read it and where the probe's `EXISTS` is rendered (`ConditionGlueRenderer` already renders a
   correlated `EXISTS` over a join path for the FK-target `@condition`, which may be the shape to
   extend).

   *Author, round 1:* the plan now has a "The probe as a command" section. `ParentRowDemand` stays
   the base-name column demand, untouched. A new `PresenceProbe(alias, List<ProbeTerm>)` command,
   with `ProbeTerm(ReachPath, GlueCall, List<ColumnRef>)`, rides beside the column list as an
   `Optional` on `CallWrap.Splice`, `CallWrap.SameRow`, `CallWrap.Multiset` (single arity only) and
   the two `LaunchSource.Correlated.Projected` records. "Rendering a probe" moves
   `ConditionGlueRenderer.reachExists` into `PathFragments` with the correlated alias as a parameter,
   so both rails share it. Taking `ReachPath` brought two limits into the presence table: a path
   through a routine hop blocks, and so does a field whose filters read its own selection
   (`ProjectionCommands.readsSelectedFieldArguments`). The table also gained the "every other
   field" row from the non-blocking note.

Non-blocking: the presence table's last row lists the blockers but not the remaining child kinds
(`ComputedField`, `RecordReadField`, `ServiceRecordField`, `PivotSlotField` and the like), which
block today through the `default` arm; saying "every other field" there would make the table total.
The `navnAlleSprak` step assumes the implementer can obtain sis's return type; if that has to be
relayed, say so where the step is.

### Round 2: Spec → Ready, sign off (session_014VWWQLWNX6uzj3c5jyCgHq, 2026-10-21)

Both round-1 findings are answered. The Goal now scopes the rule to the four child kinds, and the
migration draft lists every reading that differs from Graphitron 9. "The probe as a command" gives
the probe its own carrier beside the column demand and leaves `ParentRowDemand` alone. When this
lands, a nullable single-object child of a table type that holds nothing resolves to `null` instead
of an object of nulls, decided over the whole type. That is the same answer Graphitron 9 gave for
every sis shape named. The plan extends the existing `NullContract` and `SubtreePresence` rule, the
`sameRowNullContract` per-type fold and the condition rail's `ReachPath`/`reachExists`, and every
symbol, test and manual section it names exists.

Non-blocking, for the implementer:

- A `BatchedTableField` with `SourceShape.Record` (the data field of a `@service` or DML payload)
  uses the same rows method and fetcher builder as the `@splitQuery` arm. The Goal and the manual
  draft scope the rule to fields of table types. Gate on `SourceShape.Table`, or say in the manual
  that payload data fields collapse too.
- A single `@lookupKey` child is rejected at classification
  (`LookupKeyDirectiveResolver.resolveAtChild`). `CorrelatedLookupChain` therefore never carries a
  single child, and its probe slots stay empty.
- The table's "taking no arguments" is the operative test for a list probe. On its own,
  `readsSelectedFieldArguments` would admit a `@lookupKey` list, whose narrowing is not in
  `filters()`.
- An inline `TableField` narrowed on its own table has an empty join path. If one sits inside an
  object with a request-context-only filter, its `ReachPath` is empty, and `reachExists` reads hop 0.
  Block it or render the uncorrelated `EXISTS`.
- Where the probe's hop-alias locals are declared is left to the renderer. So is what "agree on
  its probes" in `comparePresenceColumns` compares; that the probe is present at each site is what
  the shared fetcher needs.
- The manual section is titled "Returning the parent's own row as a table type".
