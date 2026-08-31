---
id: R885
title: "Converter-diverged FK key columns emit non-compiling column comparisons"
status: In Progress
bucket: correctness
priority: 1
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# Converter-diverged FK key columns emit non-compiling column comparisons

The generator compares two database columns by writing `a.eq(b)` in the code it emits. That
only compiles when jOOQ gives both columns the same Java type. A jOOQ *converter* (configured
in a consumer's jOOQ codegen as a `<forcedType>`) changes the Java type of one column: an Oracle
`NUMBER` code column can be exposed as `String` rather than `Short`. When a converter is attached
to only one end of a foreign key, the two ends **diverge**: the referencing column stays
`Field<Short>` and the referenced column becomes `Field<String>`. The generator does not look at
either type, so it emits `l0.LANDNR.eq(table.LANDNR)`, which does not compile because
`Field<String>.eq` has no overload accepting a `Field<Short>`. The whole generated module fails to
compile, and the only way a consumer can proceed is to delete the schema field. Reported as
[issue 540](https://github.com/sikt-no/graphitron/issues/540) against `10.0.0-RC35`, blocking a
subgraph upgrade from `9.3.2`; converters on referenced key columns are ordinary in FS databases,
so other upgrades are likely blocked behind the same fault.

## The one fact the whole fix rests on

A jOOQ `Converter` is a **client-side type mapping only**. It changes what Java type jOOQ hands
you for a column; it does not change, and cannot change, the column's SQL type. `LAND.LANDNR` and
`INSTITUSJON.LANDNR` are both `smallint` in the database whether or not a converter is registered
on either of them.

Two consequences follow, and they decide the shape of everything below.

First, **the SQL the generator emits today is already correct**. `land.landnr = institusjon.landnr`
is a valid, well-typed, index-usable SQL predicate. Nothing about the generated *query* is wrong.
The fault is confined to the Java the generator writes to express that query, which is why the
symptom is a `javac` error and never a wrong answer or a runtime `SQLException`. There is no
diverged-FK schema that produces bad SQL today; the ones that would are the ones that do not
compile.

Second, **the fix must not change the emitted SQL**, and any candidate that does is the wrong
candidate. That immediately disqualifies `cast`, which puts a real SQL `CAST` around a column and
costs the index on it. It selects `coerce`, whose entire job is to reinterpret a field's Java type
while leaving the rendered SQL alone. Confirmed present in the pinned jOOQ 3.20.11:

```
public abstract <Z> org.jooq.Field<Z> coerce(org.jooq.Field<Z>);
public abstract <Z> org.jooq.Field<Z> coerce(org.jooq.DataType<Z>);
public abstract <Z> org.jooq.Field<Z> coerce(java.lang.Class<Z>);
```

This also settles the "is a diverged FK semantically dangerous" question the Backlog body raised.
It is not, and it is not this item's business: a converter that is not order-preserving already
misorders a keyset page whenever it sits on an ordering column, diverged or not. That is a
pre-existing property of putting a converter on a key, unchanged by this work. Recorded under
*Out of scope* below rather than folded in.

## Why the existing converter fixture does not catch this

`graphitron-sakila-db` already has a converter fixture: the `converter_org` / `converter_campus`
pair in `init.sql`, whose `org_code` columns are typed by the `org_code_domain` domain, with a
`<forcedType>` in `graphitron-sakila-db/pom.xml` selecting **by type** (`includeTypes:
org_code_domain`). Selecting by type applies the converter to both ends of the foreign key at once,
so both sides are `Field<String>` and no divergence arises. The consumer configuration in the issue
selects **by column path** (`includeExpression: .*\.LAND\.LANDNR`), which is what makes the two
ends diverge. The fixture therefore exercises converter binding but not converter divergence, and
no test in the tree pins the diverged shape.

## Emission sites

The reported break is one of several. `ColumnRef` already carries the per-column Java type
(`columnType()`, decided once at the catalog boundary from `Field.getType()`), so every site below
has the information it needs and simply does not consult it. This list is read off the source, not
observed: iteration 1 below exists to replace it with the set a real diverged fixture actually
reaches, and it may move in either direction. The item should shrink where a site turns out
unreachable from authored SDL, and grow where the inventory finds a site this reading missed.

The test that put a site on this list is mechanical, and worth stating so a later reader can re-run
it rather than re-derive it: the receiver's Java type and the argument's Java type are supplied by
**two different** `ColumnRef`s. Two kinds of site pass that test and are immune anyway, and both
kinds are common enough that the reader needs them named:

* **Same column on both sides.** One type cannot disagree with itself, however the converter is
  configured. This disposes of `ReentryRowsFragments.valuesJoinOn`, `TypeFetcherGenerator`'s
  bulk-update lookup `WHERE`, `SelectMethodBody`'s dispatcher `ON`,
  `ProjectionUnitRenderer`'s lookup-input `ON`, and `ServiceRowsFragments`'s projection-input `ON`.
* **Both operands erased.** `TypeFetcherGenerator`'s untyped parent-record condition emits
  `DSL.field(DSL.name("<child>")).eq(parentRecord.get(DSL.name("<parent>")))`, whose two ColumnRefs
  really are the two sides of a slot, but `DSL.field(Name)` is a `Field<Object>` and
  `Record.get(Name)` an `Object`, so the comparison is type-blind for the same structural reason
  `.onKey(Keys.<CONSTANT>)` is. This one is worth watching rather than dismissing: it is immune
  today only because nothing types it, so a later change that gives either operand a real type puts
  it back on the list.

**This enumeration was not closed when the plan was written; iteration 1 closed it, and the result is
under *Iterations* below.** The eight sites listed here came off a narrower grep than they should
have, and widening the pattern found shapes the first pass missed, including the erased site just
named. The pattern that found everything, worth recording because the obvious narrower ones do not,
is

```
grep -rn --include=*.java -o '"[^"]*\.eq(\$\?[^"]*"' graphitron/src/main/java
```

Sites that pattern surfaces and this section did not classify either way are the row-comparison
arms in `ReentryRowsFragments.keyEquality` and `RoutineWriteFetcherRenderer`, whose operands are a
target column against a lifted `RecordN` accessor, and `TypeFetcherGenerator`'s `@lookupKey` input
bindings, whose right operand is decoded input rather than a second catalog column. Iteration 1
classified each against the test above; the verdicts are under *Iterations* below, and one of them
(`RoutineWriteFetcherRenderer`) came back vulnerable rather than immune.

* `JoinFragments.emitCorrelationWhere` writes `firstAlias.<target>.eq(parentAlias.<source>)` for the
  step-0 correlation of a reach path. This is the shape the issue reports. Note that it emits an
  explicit column equality regardless of keying, where the sibling join emitters dispatch on keying
  and render a catalog foreign key as `.onKey(Keys.<CONSTANT>)`, which is type-blind and therefore
  unaffected. A correlated subquery's `WHERE` has no `.onKey` equivalent, so this site needs a real
  answer rather than a redirect.
* `JoinFragments`'s name-matched join arms (both the `On` and the `JoinBasis` overload) write the
  same column-to-column equality for an inferred, non-catalog pairing.
* `ProjectionUnitRenderer`'s pivot-multiset correlation writes `<pivotAlias>.<target>.eq(table.<source>)`.
* `DiscriminatedTableFragments`'s joined-detail `ON` chain writes `<detailAlias>.<source>.eq(<base>.<target>)`.
* `BatchedRowsFragments` compares a column against a parent-input `VALUES` cell looked up as
  `parentInput.field("<sqlName>", <ownerTable>.<COL>.getDataType())`. The lookup takes its
  `DataType` from the parent column and the comparison receiver from the child column, so a
  divergence mismatches the same way.
* The `VALUES` row machinery in `ValuesJoinRowBuilder` types each row cell from one side's
  `ColumnRef.columnType()` while `cellsCode` binds the value with the other side's `getDataType()`.
  Under divergence the two disagree, so this needs checking on the batched and lookup paths.
* `MultiTablePolymorphicEmitter.parentInputSlotPredicate` writes
  `<firstAlias>.<slot.targetSide()>.eq(parentInput.field("<slot.sourceSide().sqlName()>",
  <owner>.<sourceSide>.getDataType()))`. This is the `BatchedRowsFragments` shape above, spelled a
  second time in a second class, and it is the reason the mint exists rather than a patch at the
  first site: the same three-line rule was already due to be written twice before anyone wrote it
  once.
* `MultiTablePolymorphicEmitter.valueBoundParentWhere` writes
  `<firstAlias>.<slot.targetSide()>.eq(parentRecord.get(DSL.name("<slot.sourceSide().sqlName()>"),
  <sourceSide.columnType()>.class))`. The receiver is a column and the right operand is a **value**
  read out of a `Record`, not a `Field`, which is a shape the other seven do not have. It needs its
  own sentence in *The rule* below and it gets one.

## Design

### One mint, not one patch per site

The sites above are so many spellings of one question: *given two catalog columns, write the Java
that compares them.* Patching each site with its own type check would leave the same three-line
rule copied once per site, with no structural reason the next site would pick it up. That risk is
not hypothetical, and it is why the open-ended count above is an argument for the mint rather than a
problem for it: two of the eight listed are the same shape in two different classes, the inventory
found the second only after the first had been written up as a one-off, and a widened grep then
found further shapes still unclassified. A design whose correctness depends on having enumerated
every site is the wrong design here. The tree already
answers this class of problem by funnelling a shape through a single producer: `ValuesJoinRowBuilder`
states that "all routes go through `cellsCode`, so every VALUES cell in the generator binds as
`DSL.val(value, col.getDataType())`"; `DiscriminatedTableFragments` mints the discriminator
reference and the discriminator operand once each, and `PathFragments.parentColumnEquals` records
why the third site calls them rather than restating either: it "shares the qualification argument
and the bind typing with the assembly's other two comparison sites."

So: a single minting surface for a column-to-column equality, and every site above calls it. The
proposed home is a new `ColumnComparison` in `no.sikt.graphitron.render`, sitting at the same
below-narrowing layer `JoinFragments` describes itself as occupying. Three entry points cover every
caller found, one per shape the right operand can take:

* `equality(leftAlias, leftColumn, rightAlias, rightColumn)` for the five sites where both operands
  are aliased table columns.
* `equalityAgainstField(alias, column, otherColumnForTyping, fieldExpression)` for the
  `BatchedRowsFragments` and `MultiTablePolymorphicEmitter.parentInputSlotPredicate` shape, where the
  right operand is a `parentInput.field(...)` lookup rather than a table column but is *typed by* a
  known catalog column.
* `equalityAgainstValue(alias, column, valueColumn, valueOwnerTable, valueExpression)` for
  `MultiTablePolymorphicEmitter.valueBoundParentWhere`, where the right operand is a bare Java value
  rather than any kind of `Field`. `valueColumn` is the column the value was read from and
  `valueOwnerTable` its owner, which together spell the `DataType` the value binds at.

All three return a `CodeBlock` and all three apply the same rule. Callers keep their own
AND-chaining, their own alias resolution, and their own surrounding syntax; only the equality itself
moves.

### The rule

Let `L` be the receiver's `ColumnRef.columnType()` and `R` the argument's.

* If either is `null`, emit `left.eq(right)` unchanged. `ColumnRef.columnType()` is documented
  nullable for hand-built placeholder refs whose type is never read, and a spurious coerce on one
  would be worse than the status quo.
* If `L.equals(R)`, emit `left.eq(right)` unchanged. This is every schema that compiles today, so
  every approved generated-source fixture stays byte-identical and the diff carries no churn.
* Otherwise emit `left.eq(right.coerce(left))`.

Coerce the **argument onto the receiver**, always. The alternative rules considered were "coerce
the converted side to the raw side" and "coerce to the referenced side", and both were rejected for
the same reason: neither "raw" nor "referenced" is recoverable from a `TypeName`, so both need a
second fact threaded to the mint that the receiver-wins rule does not. Since neither operand is a
bind value, the direction is invisible in the emitted SQL, so the rule can be chosen for
mechanical reviewability rather than semantics.

The bind-site companion rule, stated so `BatchedRowsFragments` and `ValuesJoinRowBuilder` do not
drift apart: **a value binds at the `DataType` of the column it was read from, and the comparison
coerces.** Binding is where a converter genuinely applies (the value really does round-trip through
it), which is what the `converter_org` / `converter_campus` split-query coverage already pins;
coercion is only about the Java types lining up afterwards. Getting these backwards is how the
original converter bug in that fixture arose, so the two are worth naming separately in the mint's
javadoc.

#### The value operand

`valueBoundParentWhere` is the one site where the right operand is a bare Java value, so
`.coerce(...)` has nothing to attach to: there is no `Field` yet. The answer is not a third rule. It
is the two rules above applied in order, which is why this shape needs an entry point but not a new
policy:

1. Bind the value at the `DataType` of the column it was read from, the companion rule verbatim. That
   is `DSL.val(<value>, <valueOwnerTable>.<valueColumn>.getDataType())`, the same mint
   `ValuesJoinRowBuilder.cellsCode` uses for every `VALUES` cell and
   `DiscriminatedTableFragments.discriminatorValue` for every discriminator literal. The value now
   *is* a `Field`, typed at the source column and rendering through the source column's converter.
2. Coerce that field onto the receiver, the equality rule verbatim.

So the diverged emission is
`left.eq(DSL.val(<value>, <owner>.<valueColumn>.getDataType()).coerce(left))`, and the two guards are
unchanged: null or equal types emit `left.eq(<value>)` exactly as today, which keeps this site's
approved output byte-identical along with everyone else's.

Reading the value at the *receiver's* type instead, by passing `targetSide().columnType()` as the
`Class` argument to `parentRecord.get(Name, Class)`, also compiles and is shorter. It is rejected: it
routes the value through `Convert.convert` between two user types, which happens to work for the
`Long`/`String` pair in the fixture and is not guaranteed for an arbitrary converter's user type. The
bind-then-coerce form asks jOOQ only for conversions a registered `Converter` already declares.

Both halves of the claim were checked against jOOQ 3.20.11 by rendering the predicate rather than by
reading the javadoc. A `String` value bound at a `BIGINT`-with-`Converter` `DataType` and coerced
onto a `Field<Long>` renders `"c"."org_code" = 186` with inlined parameters and `"c"."org_code" = ?`
with a single bind, in both cases identical to the undiverged `child.eq(186L)`. The column-to-column
form renders `"c"."org_code" = "p"."org_code"`, identical to the undiverged comparison. That is the
same no-SQL-change property the design rests on, now measured at both operand shapes.

One plumbing note, since the section below claims the mint needs none. `valueBoundParentWhere` takes
only `(String firstAlias, List<JoinSlot.FkSlot> slots)` and so has no owner `TableRef` for step 1.
Its sole caller `singleBranchCorrelationWhere` does not either, but *that* method's caller already
holds `parentKeyOwnerTable` as a parameter, so this is one argument threaded down two frames from a
value already in scope. No new model field and nothing new resolved.

### Alternative considered: jOOQ's implicit path join

The reporter supplied the 9.3.2 cross-check on the issue, and it names a real alternative rather
than a historical curiosity. 9.3.2 never wrote a column equality at all. It navigated the FK
through jOOQ's generated path method and let jOOQ render the predicate:

```java
// OrganisasjonDBQueries.java, graphitron 9.3.2
var _a_institusjon_2769165829_land = _a_institusjon.land().as("land_169829664");
```

`institusjon.land()` is the generated navigation method for the `LAND_INSTITUSJON` foreign key, so
the predicate lives inside jOOQ's path-join machinery and is type-blind at the Java layer for the
same structural reason the `.onKey(Keys.<CONSTANT>)` arms are. The SQL it produced is the plain
`land.landnr = institusjon.landnr` this section argues was always correct. The cross-check therefore
confirms the design rather than competing with it: the predecessor's output is exactly what a
coerced equality renders, so `coerce` restores 9.3.2's emitted SQL byte for byte.

Adopting the mechanism, rather than just matching its output, is the alternative, and it is
rejected on three counts:

* **Path methods can be absent.** They are a codegen feature a consumer configures, and this
  reactor's own fixture already switches half of them off: `graphitron-sakila-db/pom.xml` sets
  `<implicitJoinPathsToMany>false</implicitJoinPathsToMany>` because sakila's mutual store/staff
  FKs and the category self-FK generate colliding method names that jOOQ flags on every codegen
  run. A generator that emits calls to those methods would break on that configuration, and on the
  ordinary schemas that provoke it.
* **They cover only catalog FKs.** The name-matched arms pair columns with no `ForeignKey` behind
  them, so no path method exists to navigate. Those sites need the mint regardless, and having two
  mechanisms for one question is the outcome the "one mint" argument above exists to avoid.
* **They do not reach the shapes that actually break.** A correlated subquery's `WHERE`, a
  `VALUES`-joined DataLoader batch, and a pivot multiset are not path navigations, and there is no
  path-method spelling of any of them.

Structurally the rewrite has already taken the other road: the pom comment records that no catalog
consumer navigates via path methods and that "the rewrite reads `ForeignKey` metadata off the
`Keys` class directly", and a grep of the generator confirms it emits no path-method call anywhere.
So this is a road not taken by design, not a mechanism the rewrite dropped by accident.

### Where divergence is decided

`BuildContext.resolveFkColumnRefs` resolves both ends of every FK through `catalog.findColumn` and
carries the catalog-decoded `columnType()` onto each side of every `JoinSlot.FkSlot`. So both
operands of every affected comparison already hold a live, real type, and the mint needs no new
plumbing and no new model field. The single exception is the owner `TableRef` that
`valueBoundParentWhere` needs in order to spell a `DataType`, threaded down two frames from a value
its caller's caller already holds; see *The value operand* above. Divergence is a derived property
of a slot, read where the
comparison is written; it is deliberately **not** lifted to a `boolean diverged()` on `FkSlot`,
because the non-FK callers (the name-matched arms, the batched field lookup) compare `ColumnRef`s
that never form a slot, and a slot-level flag would serve only some of them.

A store-sourced reader gets the same answer: `sql_column.binding_type` is documented as "the fully
qualified Java type jOOQ binds the column to, as `Field.getType()` reports it", which is the
post-converter type, and `ColumnRef.decodeBindingType` recovers it. Worth a confirming check in
iteration 1 rather than an assumption.

## Fixture

The diverged shape can be built with **one new table and no build-configuration change at all**.
`converter_org.org_code` is typed `org_code_domain`, a domain over `bigint`, and the existing
`<forcedType>` converts it to `String` by selecting on that domain type. A new child table whose FK
column is declared as plain `bigint` rather than as the domain therefore escapes the converter and
generates as `Long`, while its referenced column stays `String`. That is exactly the reported
divergence, reached without touching `graphitron-sakila-db/pom.xml` and without a new `Converter`
class.

Probed against the live fixture database before writing this section; PostgreSQL accepts the
foreign key and the join resolves:

```sql
CREATE TABLE _probe_child (
    id       serial PRIMARY KEY,
    org_code bigint NOT NULL REFERENCES converter_org(org_code)
);
INSERT INTO _probe_child (org_code) VALUES (186);
SELECT c.id, o.org_name FROM _probe_child c JOIN converter_org o ON o.org_code = c.org_code;
-- 1 | UiT
```

This is why the fixture is a new table rather than a re-pointed `<forcedType>`: narrowing the
existing rule to one column by path would convert `converter_campus.org_code` back to `Long` and
destroy the both-ends-converted coverage that pins the split-query bind fix. The two fixtures test
different things and both are wanted.

Schema fixtures in `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`. Note the
existing converter fixtures are `@splitQuery` in both directions plus a `@sourceRow` lifter, and
carry an explicit `@reference(path: [{key: ...}])`. The reported shape is none of those: a plain
single-cardinality reference with **no** `@splitQuery` and **no** `@reference`, resolved off the
unambiguous FK. That combination is what reaches `emitCorrelationWhere`, and it is the gap.

As shipped, the fixture grew past that minimum in two directions, both driven by iteration 1's
inventory rather than planned here. It carries the reported reference and its `@splitQuery` sibling
in both cardinalities (four fields across `DivergedRefChild` and `DivergedRefOrg`), and it carries
the diverged key under a multi-table polymorphic child field in both cardinalities, which needed a
second small table (`diverged_child_label`) to supply the sibling participant. The list polymorphic
field is what reaches `parentInputSlotPredicate` and the single one `valueBoundParentWhere`.

One fixture constraint is worth recording because it cost two build cycles and reads as a fault in
the work under test. A multi-table polymorphic field's stage-1 statement unions one branch per
participant and projects each branch's key as `__pk0__`, so the participants' primary keys must
agree on **both** the Java type and the SQL type, not merely on arity. A converter is exactly what
pulls those two demands apart, which is why this bites here and not in the fixtures that predate it:

* Keyed on an integer, the sibling makes the union's branches `Record3<String,Integer,Integer>` and
  `Record3<String,String,String>`, and the generated module does not compile.
* Keyed on a `varchar`, it compiles (both branches are `String` in Java) and then fails at runtime
  with `UNION types character varying and bigint cannot be matched`, because
  `converter_org.org_code` is a `bigint` that only *generates* as `String`.

Declaring the sibling's key on `org_code_domain` settles both at once, and is what a real schema
keyed on a shared code domain looks like anyway. Neither failure message names the diverged key or a
comparison, so a future reader hitting one should suspect the fixture before the mint.

The sibling is also left with no rows, mirroring how no `category_label` row shares the `CategoryRef`
fixture's isolating `category_id`. The field is single-cardinality, so a populated sibling would make
which participant answers depend on how the two key values happen to sort, and the assertion would be
reading a numeric accident. A populated sibling beside a populated diverged branch is covered by the
list field instead.

One operational note for anyone re-running this. Adding a table to `init.sql` mid-session does not
by itself put it in the jOOQ catalog: the sandbox seeds `rewrite_test` at session start, so the
build fails with "table `diverged_ref_child` could not be resolved in the jOOQ catalog" and a
did-you-mean list, which reads exactly like an authoring mistake. The recovery is the re-seed plus
`rm -rf` of the generated sources documented under "Catalog-jar clobber" in
`.claude/web-environment.md`.

## Iterations

All three iterations have shipped. What follows is what they found, kept because it is the closed
enumeration the *Emission sites* section above deliberately did not provide, and because it records
which sites went onto the mint with no fixture reaching them.

### Iteration 1: the recorded inventory

The fixture is the one described under *Fixture* above, grown once during the iteration (see below).
Built without the mint, it fails the compilation tier with exactly five `javac` errors across four
generated files, every one of them `no suitable method found for eq(...)`:

[cols="1,1"]
|===
| Generated site | Emission site

| `types/DivergedRefChild.$project`, the `organisation` arm
| `JoinFragments.emitCorrelationWhere`, child to parent

| `types/DivergedRefOrg.$project`, the `children` arm
| `JoinFragments.emitCorrelationWhere`, parent to child list

| `fetchers/DivergedRefChildFetchers`, the `splitOrganisation` rows method
| `BatchedRowsFragments.fromBridgeAndParentJoin`

| `fetchers/DivergedRefOrgFetchers`, the `splitChildren` rows method
| `BatchedRowsFragments.fromBridgeAndParentJoin`

| `fetchers/DivergedRefOrgFetchers`, the `polyChildren` stage-1 union's `DivergedChildRef` arm
| `MultiTablePolymorphicEmitter.parentInputSlotPredicate`
|===

The last row is the outcome this iteration's two-directional charter existed to produce. The
polymorphic topology was added to the fixture during the iteration rather than being taken on faith:
a diverged key under a multi-table polymorphic child field, whose stage-1 union carries the diverged
`DivergedChildRef` arm beside the undiverged `DivergedCampusRef` arm in the same statement. That
juxtaposition is worth more than a second fixture would be, because it pins the two rules against
each other: the diverged arm must coerce and the undiverged arm must stay byte-identical.

**The store-sourced path does see the divergence.** `CatalogColumn.javaTypeName` is documented as the
bound Java type "as jOOQ names it", which is the post-converter type, and `ColumnRef.decodeBindingType`
recovers it. The command tier therefore gets its own `ColumnComparison.equality` overload that decodes
rather than a second copy of the rule.

**Two sites went onto the mint with no fixture reaching them, deliberately.**
`MultiTablePolymorphicEmitter.valueBoundParentWhere` was reached by extending the fixture with a
single-cardinality polymorphic reference over the diverged key, so it is covered. The two that
remain uncovered by a fixture are `ProjectionUnitRenderer`'s pivot-multiset correlation (plus the
pivot arm of `BatchedRowsFragments`) and `DiscriminatedTableFragments`'s joined-detail `ON`. Both
need a topology orthogonal to divergence to reach at all (a pivot attribute table, a single-table
discriminated interface with a joined detail), so reaching them means building a second fixture
whose only new fact is that the mint is called from a third and fourth place. The trade taken is to
move them onto the mint unreached: the mint is a total function over two types with its own per-arm
unit coverage, so what a fixture would add there is call-site wiring, and that is what the compiler
checks. `JoinFragments`'s name-matched arms are the same case for the same reason.

**Sites the widened grep surfaced and this iteration classified as immune**, each because the
receiver's type and the argument's type come from the *same* `ColumnRef`:
`ReentryRowsFragments.valuesJoinOn` and `keyEquality` (the `keys` `RecordN` is typed from the same
`correlation.columns()` the comparison reads), `RoutineWriteFetcherRenderer`'s single-column and
row-value arms are *not* immune and are recorded below instead, `ProjectionUnitRenderer`'s
lookup-input `ON`, `ServiceRowsFragments`'s projection-input `ON`, `SelectMethodBody`'s dispatcher
`ON`, `TypeFetcherGenerator`'s bulk-update lookup `WHERE` and its `MapGroup` `@lookupKey` binding
(which binds at the receiving column's own `DataType`, the companion rule already), and
`ConditionGlueRenderer.columnCompare` (likewise). `TypeFetcherGenerator`'s untyped parent-record
condition stays immune-because-erased, as *Emission sites* records.

**One site is vulnerable in principle and is not fixed here.**
`RoutineWriteFetcherRenderer.keysInCondition` compares hop 0's target-side columns against values
read off a `keys` local typed from the *source* side, so the two types really do come from two
different columns. It is not on the mint for two reasons that hold together. First, its three
spellings are `Field.eq(value)`, `Field.in(Collection)` and `Row.eq(Row)`, and the last two are
comparison shapes the mint's three entry points do not spell; covering them is a second design, not
a fourth caller. Second, the value's source column is a column of the *routine's own result table*,
for which the site holds no `TableRef`, so the bind-at-source rule has nothing to spell a
`DataType` from. Reaching it needs a routine-write coordinate whose captured key is diverged, which
no fixture in the tree builds. Recorded under *Out of scope* below rather than left silent.

### Iterations 2 and 3: the mint and the sites

`ColumnComparison` in `no.sikt.graphitron.render` carries the rule and the three entry points the
design named, plus the `CatalogColumn` overload iteration 1 turned up. Every site named in
*Emission sites* is on it. `valueBoundParentWhere` took the one threaded argument the design
predicted, `parentKeyOwnerTable`, down two frames from `singleBranchCorrelationWhere`'s caller.

No approved generated output moved, which is the check that the null and equal guards are right.

## Tests

Per the tier rubric in `docs/architecture/how-to/testing.adoc`, top-down:

* **Compilation tier** is the primary gate and needs no test class: the symptom *is* a compile
  failure, so the schema fixtures plus `mvn compile -pl :graphitron-sakila-example -Plocal-db` are
  the assertion. This is the tier that would have caught the reported bug.
* **Execution tier**, a new `@Test` in `GraphQLQueryTest`: the diverged reference resolves and
  returns the right rows against PostgreSQL. This is what proves `coerce` did not disturb the SQL,
  which is the whole safety claim of the design. One test per topology the inventory found
  reachable, not one per emission site.
* **Unit tier**, a renderer arm test on `ColumnComparison`: the three rule branches (either type
  null, types equal, types diverged) asserted directly on record literals, across all three entry
  points, since the value operand's diverged branch emits a different shape from the other two and
  its undiverged branch must stay byte-identical. The tier guide names renderer arm tests as the
  preferred home for per-arm structural assertions on command-driven emission, and this mint is a
  total function over a small input, so it fits exactly.
* **Execution tier, second test**, if and only if iteration 1 gets a diverged key under a polymorphic
  parent: the same resolves-and-returns-the-right-rows assertion for that topology. The value-operand
  emission is the one arm whose SQL neutrality rests on a bind rather than on a column reference, so
  it is the arm most worth proving against a real database rather than only in a render assertion.
* **Pipeline tier**: nothing owed. The classification of a diverged reference is identical to a
  non-diverged one, which is the point; a pipeline case asserting that would pin a non-difference.

### What shipped

All of the above, including the conditional second execution test, since iteration 1 did get a
diverged key under a polymorphic parent. Concretely: `ColumnComparisonTest` (ten cases across four
entry points, both the diverged and the undiverged branch of each) and four `@Test` methods in
`GraphQLQueryTest`, one per topology the inventory reached (correlated single, DataLoader single,
list in both flavours side by side, and the polymorphic pair). The compilation tier needed no test
class as predicted.

Two existing assertions moved, and neither is the churn the design warned about. The pinned line
number in `FixtureWarningsGateTest` shifted because the fixture added SDL above the field it names.
`TypeFetcherGeneratorTest`'s two per-branch `parentInput` JOIN assertions now expect a coerce,
because the fixture they run on invents a single-column `Timestamp` primary key on `film_actor` to
reach the arity-1 DataLoader path while the participant columns it compares against are `Integer`.
Those two types genuinely disagree, so the mint fires; a real catalog cannot produce that pair,
because both ends of a foreign key share a SQL type. The assertion is spelled through one helper
that says so, rather than restating the coerce twice.

No approved generated output moved.

## Out of scope

* **Converters on ordering keys.** A converter that is not order-preserving misorders a keyset page
  whether or not the FK is diverged. Pre-existing, orthogonal, and worth its own Backlog item if
  anyone hits it.
* **Restoring 9.3.2's mechanism.** The reporter has since supplied what 9.3.2 emitted, so the
  question the Backlog body left open is answered and is no longer out of scope; it is evidence, and
  it lives under *Alternative considered: jOOQ's implicit path join* above. Adopting the path-join
  mechanism itself is what stays out of scope, for the three reasons given there.
* **Diverged non-key column comparisons.** This item covers columns compared as join or correlation
  operands. A converter that diverges two columns compared somewhere else entirely is the same class
  of fault, but there is no reported instance and no fixture for one; the mint is the place a future
  instance would land.
* **`RoutineWriteFetcherRenderer.keysInCondition`.** Found vulnerable by iteration 1's inventory and
  deliberately left off the mint; see the two reasons recorded there. Worth its own Backlog item if
  a consumer hits it, and the mint is where the `Field.eq(value)` third of it would land once the
  routine's result table is reachable as a `TableRef`.

## Reviewer findings

### Round 1, Spec → Ready: revisions requested

**Question 1 (goal and viability) passes.** What changes for a consumer is stated without needing
the phase list: a consumer whose jOOQ codegen attaches a converter to one end of a foreign key can
keep the schema field instead of deleting it, because the generated module compiles again, and the
SQL that module issues is unchanged. Every claim I could check held. `Field.coerce` has exactly the
three overloads quoted, in the pinned jOOQ 3.20.11 jar. `ColumnRef.columnType()` is carried from the
catalog boundary and is documented nullable for the placeholder refs the null guard names.
`BuildContext.resolveFkColumnRefs` does resolve through `catalog.findColumn` and does put
`ce.columnType()` on both sides of every `JoinSlot.FkSlot`, so the mint needs no new plumbing. The
`sql_column.binding_type` comment reads as quoted. The `graphitron-sakila-db/pom.xml` claims are
verbatim, `implicitJoinPathsToMany` included. The converter fixture is `@splitQuery` in both
directions plus a `@sourceRow` lifter with an explicit `@reference(path:)`, so the plain
no-directive single-cardinality gap is real. I re-ran the fixture probe against the live database and
got the same answer, `1 | UiT`, so the one-new-table fixture is viable as described.

**Question 2 (architectural fit) fails: the emission inventory is short by two divergence-vulnerable
sites, one of them needing a rule the two entry points cannot spell, and iteration 1's charter is
written so the miss cannot be discovered.**

`MultiTablePolymorphicEmitter` carries two column-to-column comparisons over
`List<JoinSlot.FkSlot>`, both live (called from lines 1438 and 1440, and from 2019 and 2021), and
neither is on the list:

* `parentInputSlotPredicate` (around line 2041) emits
  `<firstAlias>.<slot.targetSide()>.eq(parentInput.field("<slot.sourceSide().sqlName()>",
  <owner>.<sourceSide>.getDataType()))`. That is the `BatchedRowsFragments` shape from the list,
  spelled a second time in a second class: receiver typed by the child column, argument typed by the
  parent column, mismatching under divergence for the same reason. `equalityAgainstField` covers it
  as designed; it is simply not named.
* `valueBoundParentWhere` (around line 1456) emits
  `<firstAlias>.<slot.targetSide()>.eq(parentRecord.get(DSL.name("<slot.sourceSide().sqlName()>"),
  <slot.sourceSide().columnType()>.class))`. This is a third shape, and the design does not reach it.
  The right operand is a *value*, not a `Field`, so `.coerce(...)` has nothing to attach to, and the
  spec needs to say what governs it.

The omission is specific, not a general audit failure, and worth recording so the revision is
cheap: every other emitted equality I looked at supplies both the receiver and the argument's type
from the *same* `ColumnRef`, and is therefore immune. That covers
`ReentryRowsFragments.valuesJoinOn`, `TypeFetcherGenerator`'s bulk-update lookup `WHERE`,
`SelectMethodBody`'s dispatcher `ON`, `ProjectionUnitRenderer`'s lookup-input `ON`,
`ServiceRowsFragments`'s projection-input `ON`, and `MultiTablePolymorphicEmitter`'s own
`parentSourceKey` arms at lines 1485 and 2069. `ConditionGlueRenderer.columnCompare` is a bind
against the receiving column's own type, which is the companion rule already, not a new site. So the
inventory is eight sites, not six.

The charter direction is the half that makes this blocking rather than a note. Iteration 1's stated
deliverable is "a decision on which of the six sites a diverged key can actually reach", and it says
"the item should shrink where a site turns out unreachable". The fixture driving that inventory is
one non-polymorphic single-cardinality reference, so its `javac` list can only surface sites that one
topology reaches; a polymorphic site cannot appear in it, and nothing in the charter invites the
implementer to look wider. Followed exactly, the plan ships the mint plus five of eight callers, with
the polymorphic arms still non-compiling under divergence and no record that they were considered.
That is precisely the "no structural reason a further site would pick it up" failure the *One mint*
argument exists to prevent, which is why it lands on question 2 rather than on
scope.

**What would satisfy it**

* Name both `MultiTablePolymorphicEmitter` sites under *Emission sites*.
* In *The rule*, state the answer for the `parentRecord.get(Name, Class)` shape, whether as a third
  entry point or as an explicit precedence between the coerce rule and the bind-at-source rule. This
  is the one part that is design, not bookkeeping, and it is the author's to settle.
* Make iteration 1's charter two-directional, so the inventory may grow as well as shrink. It is the
  iteration whose whole purpose is to replace a read-off-source list with an observed one, and it
  currently forbids half of that.

**Non-blocking**

* Corrected in this commit: the *One mint* section attributed the quoted phrase about sharing "the
  qualification argument and the bind typing" to `DiscriminatedTableFragments`. The phrase is in
  `PathFragments.parentColumnEquals`; `DiscriminatedTableFragments` holds the mints it refers to. The
  precedent the section cites is real either way.
* The first of the three counts against adopting jOOQ's path-join mechanism is the weakest of the
  three: `implicitJoinPathsToMany` is off in the fixture, but 9.3.2's `institusjon.land()` is a
  to-one navigation, which that flag leaves generated. The other two counts (name-matched pairs have
  no `ForeignKey` to navigate, and a correlated `WHERE`, a `VALUES` batch and a pivot multiset have no
  path spelling) each carry the rejection on their own, so nothing needs to change here.

### Round 2, revisions applied by the reviewer session at the user's direction

The user directed the reviewer session that raised round 1 to apply the round-1 revisions itself
rather than hand them back. Recorded here because it has a bookkeeping consequence: the session that
wrote the round-1 findings has now also written plan-body prose, so it is an author on this file and
cannot take the Spec → Ready gate. That gate needs a third session, reviewing the plan as it now
stands rather than re-reading round 1.

What changed, against the three asks:

* Both `MultiTablePolymorphicEmitter` sites are named under *Emission sites*, which lists eight
  rather than six. *Emission sites* also states the mechanical test that put a site on the list
  (receiver type and argument type come from two different `ColumnRef`s) and names the sites that
  test excludes, in two buckets: same column on both sides, and both operands erased.
* The enumeration is now explicitly **open**, and the closed one is iteration 1's deliverable. This
  is a correction to the round-1 finding, which asserted "the inventory is eight sites, not six" as
  though the sweep were finished. It was not. That count came off a grep pattern narrow enough to
  miss `TypeFetcherGenerator`'s untyped parent-record condition, two row-comparison arms in
  `ReentryRowsFragments` and `RoutineWriteFetcherRenderer`, and the `@lookupKey` input bindings.
  Widening the pattern found them; classifying all of them is more than a review pass can stand
  behind, so the section records the grep that finds every candidate and hands the classification to
  iteration 1 rather than implying a closed list. The *One mint* argument is stated to survive this,
  and in fact leans on it: a design that needed the enumeration closed would be the wrong design.
* The value-operand rule is settled in a new *The value operand* subsection. The round-1 finding
  framed this as a conflict between the coerce rule and the bind-at-source rule. That diagnosis was
  wrong, and the correction is the substance of the resolution: the two rules compose in order.
  Bind the value at the source column's `DataType`, which turns it into a `Field`, then coerce that
  field onto the receiver. No new policy, one new entry point. Reading the value at the receiver's
  type is the shorter alternative and is rejected in the subsection, because it leans on
  `Convert.convert` bridging two user types, which the fixture's `Long`/`String` pair happens to
  satisfy and an arbitrary converter's user type does not.
* Iteration 1's charter is two-directional, and it now names the polymorphic topology as the one
  case known in advance to sit outside the reported fixture's reach, with an explicit either/or:
  extend the fixture, or record why those two sites went onto the mint unreached. Silence is ruled
  out.

Two claims in the new prose were measured rather than reasoned, since the design's whole safety
argument is that the emitted SQL does not move. Rendering the predicate under jOOQ 3.20.11: a
`String` bound at a `BIGINT`-with-`Converter` `DataType` and coerced onto a `Field<Long>` renders
`"c"."org_code" = 186` inlined and `"c"."org_code" = ?` with one bind, both identical to the
undiverged `child.eq(186L)`; the column-to-column form renders `"c"."org_code" = "p"."org_code"`,
identical to the undiverged comparison. The figures are in *The value operand*.

The owner-`TableRef` plumbing note is the one place the revision qualifies an existing claim rather
than extending it. *Where divergence is decided* said the mint needs no new plumbing;
`valueBoundParentWhere` needs an owner `TableRef` to spell a `DataType`, and while it is threaded
from a value already in scope two frames up, that is still an argument the site does not have today.
Both sections now say so.
