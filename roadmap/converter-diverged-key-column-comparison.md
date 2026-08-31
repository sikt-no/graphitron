---
id: R885
title: "Converter-diverged FK key columns emit non-compiling column comparisons"
status: Spec
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
reaches, and the item should shrink where a site turns out unreachable from authored SDL.

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

## Design

### One mint, not six patches

The six sites above are six spellings of one question: *given two catalog columns, write the Java
that compares them.* Patching each site with its own type check would leave the same three-line
rule copied six times, with no structural reason a seventh site would pick it up. The tree already
answers this class of problem by funnelling a shape through a single producer: `ValuesJoinRowBuilder`
states that "all routes go through `cellsCode`, so every VALUES cell in the generator binds as
`DSL.val(value, col.getDataType())`"; `DiscriminatedTableFragments` mints the discriminator
reference and the discriminator operand once each so the three comparison sites that need them
"share the qualification argument and the bind typing rather than restating either."

So: a single minting surface for a column-to-column equality, and every site above calls it. The
proposed home is a new `ColumnComparison` in `no.sikt.graphitron.render`, sitting at the same
below-narrowing layer `JoinFragments` describes itself as occupying. Two entry points cover every
caller found:

* `equality(leftAlias, leftColumn, rightAlias, rightColumn)` for the five sites where both operands
  are aliased table columns.
* `equalityAgainstField(alias, column, otherColumnForTyping, fieldExpression)` for the
  `BatchedRowsFragments` shape, where the right operand is a `parentInput.field(...)` lookup rather
  than a table column but is *typed by* a known catalog column.

Both return a `CodeBlock` and both apply the same rule. Callers keep their own AND-chaining, their
own alias resolution, and their own surrounding syntax; only the equality itself moves.

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

### Where divergence is decided

`BuildContext.resolveFkColumnRefs` resolves both ends of every FK through `catalog.findColumn` and
carries the catalog-decoded `columnType()` onto each side of every `JoinSlot.FkSlot`. So both
operands of every affected comparison already hold a live, real type, and the mint needs no new
plumbing and no new model field. Divergence is a derived property of a slot, read where the
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

## Iterations

**Iteration 1: fixture and inventory, no fix.** Add the diverged table to `init.sql` and the
minimum schema fixtures, and record what actually breaks. This is the iteration that converts the
six-site list above from a reading of the source into a fact. It is expected to fail the
compilation tier, deliberately and visibly, and its deliverable is the recorded `javac` error list
plus a decision on which of the six sites a diverged key can actually reach from authored SDL. Some
may turn out unreachable, and the item should shrink rather than emit dead handling for them. Also
confirm here whether the store-sourced path sees the divergence.

**Iteration 2: the mint, and the sites the inventory proved reachable.** Introduce
`ColumnComparison`, move the reachable sites onto it, and get the fixture compiling. Existing
approved output must not move; if it does, the equality rule fired on a non-diverged pair and the
null/equal guards are wrong.

**Iteration 3: the remaining reachable sites and the execution proof.** Sites the inventory reached
but iteration 2 did not need, plus the execution-tier test that the coerce changed no SQL and no
rows.

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
  null, types equal, types diverged) asserted directly on record literals. The tier guide names
  renderer arm tests as the preferred home for per-arm structural assertions on command-driven
  emission, and this mint is a total function over a small input, so it fits exactly.
* **Pipeline tier**: nothing owed. The classification of a diverged reference is identical to a
  non-diverged one, which is the point; a pipeline case asserting that would pin a non-difference.

## Out of scope

* **Converters on ordering keys.** A converter that is not order-preserving misorders a keyset page
  whether or not the FK is diverged. Pre-existing, orthogonal, and worth its own Backlog item if
  anyone hits it.
* **What 9.3.2 emitted.** The reporter states it compiled and was correct, but the shape is not
  recorded and the legacy generator is not in this reactor. Since the SQL is fixed by the schema and
  the fix is a pure Java-type reconciliation, the emitted SQL matches any correct predecessor
  regardless of how it spelled it, so recovering the old spelling would not change the design. Not
  worth chasing; noted because the issue raises it.
* **Diverged non-key column comparisons.** This item covers columns compared as join or correlation
  operands. A converter that diverges two columns compared somewhere else entirely is the same class
  of fault, but there is no reported instance and no fixture for one; the mint is the place a future
  instance would land.
