---
id: R926
title: "The @nodeId key landing is asserted, never verified"
status: Backlog
bucket: bug
priority: 3
theme: nodeid
depends-on: []
created: 2026-09-07
last-updated: 2026-09-07
---

# The @nodeId key landing is asserted, never verified

## Goal

A `@nodeId(typeName: T)` filter whose `@reference` path does not reach `T`'s table, or reaches it and
lands `T`'s key on a column of a different Java type, becomes a build-time refusal naming both columns
and both types. Today all of those schemas are accepted with zero errors, and the consumer finds out
either from their own `javac` (a column that does not exist on the table the predicate was written
against, or a `Row9<..., Short>` compared against a `List<Row9<..., Integer>>`) or not at all, because
a mislanded path can also emit SQL that is correct by coincidence.

*Landing* is the per-position fact the classifier computes for such a filter: for each column of the
target *node type*'s key (a node type being a GraphQL type carrying `@node`, whose key columns are its
table's primary key or the columns `@node(keyColumns:)` names), which column the decoded id's value at
that position binds against. Every position landing on a column of the filtered row's own table makes
the predicate a local tuple comparison; any position landing nowhere makes it a correlated `EXISTS` on
the node type's own table. Landing is the whole of that arm choice, and it is computed by matching SQL
column names along the path's foreign-key hops.

Two things it never checks. It does not check that the path's last hop reaches `T`'s table at all, so a
path that stops short lands the key on whatever the intermediate table happens to have named like it.
And it matches by name only, so two columns that share a name and disagree on Java type count as one
column.

A minimal pair over the sakila fixtures. The first form is in the tree today
(`TranslatedFkTargetRailGatesPipelineTest`) and is what the author means:

```graphql
input FilmFilter {
  categoryRef: ID! @nodeId(typeName: "Category") @reference(path: [
      {key: "film_category_film_id_fkey"},
      {key: "film_category_category_id_fkey"}
  ])
}
```

Drop the last hop and the path stops on `film_category` instead of `category`:

```graphql
input FilmFilter {
  categoryRef: ID! @nodeId(typeName: "Category") @reference(path: [
      {key: "film_category_film_id_fkey"}
  ])
}
```

`Category`'s key column is `category_id`, which the one-hop path's arrival (`film_category.film_id`)
does not carry, so nothing lands and the predicate binds remotely: `EXISTS (SELECT 1 FROM film_category
WHERE film_category.film_id = film.film_id AND film_category.category_id IN (<decoded>))`. That
compiles, and the SQL is right, because `film_category` happens to carry a `category_id` column of the
same type, it being the child column of that table's own foreign key to `category`. Point the same
truncated path at a table that carries no such column and the identical classification emits
`FILM.CATEGORY_ID`, and the consumer's build fails.

So the outcomes today, by what the terminal table happens to hold:

| What the terminal table holds at a key position | Outcome today |
|---|---|
| no column of that name | uncompilable generated Java |
| a column of that name, of a different Java type | uncompilable generated Java |
| a column of that name and type, holding a different value | silently wrong SQL |
| a column of that name and type, holding the same value | correct, by coincidence |

The last two rows are why this is a verification gap and not a codegen crash. They are the same
classification, and graphitron has no way to tell them apart: the name and the type agree in both, and
whether the column carries the value the author meant is a fact about the consumer's schema semantics.
Nothing today separates "graphitron checked that this predicate binds the columns the author meant"
from "it happened to". When this item lands, all four rows are refusals at `graphitron:generate` time,
the fourth included, because a coincidence graphitron cannot see is not one it can keep emitting on
purpose.

## The path's terminal table: consume the verdict that already exists

The check does not have to be written, only read. `BuildContext.parsePath` and
`BuildContext.parseExplicitPath` both compute a `BuildContext.TerminalTargetVerdict` against the
`returnTableRef` their caller passes, and `NodeIdLeafResolver.resolveFkJoinPath` already passes the
resolved target table on both of its explicit routes. So the fact is computed for every explicitly
routed `@nodeId` leaf in the tree today and then dropped: the resolver reads `ParsedPath.hasError()`
and nothing else. `TerminalTargetVerdict`'s own javadoc says as much, that callers with their own
terminal-target invariant keep their checks and ignore the verdict, and this coordinate has no such
check.

Read `terminalTargetVerdict()` in the two explicit arms of `resolveFkJoinPath`
(`Route.UniformReference` and `Route.ParticipantRoute`) and return `PathResolution.Refused` on a
`Mismatch`. `Route.AutoDiscover` needs nothing: `JooqCatalog.findOutgoingFkToTable` searches *by* the
target table, so its single hop lands there by construction.

Refusing from inside those arms, rather than after the walk returns, is also what keeps the
author-owned escape correct with no new branch. `NodeIdLeafResolver.resolve` already routes a
`PathResolution.Refused` through the `@condition(override: true)` fork, whose rule is that only an
undiscovered route escapes into `Resolved.AuthorOwnedPredicate` and a stated route that failed to
resolve stays a rejection, on the grounds that naming a foreign key asks the build to check it. A path
that lands on the wrong table is a stated route that failed, and the existing fork classifies it
correctly without being told.

The verdict's rendered text is not reusable here. `TerminalTargetVerdict.Mismatch#diagnostic` is worded
for the output-projection rail that mints it ("the field's return type", "a `$project` overload typed
for the return table"), which is not what a filter leaf's author wrote. The arm carries the three names
and formats from them, so this needs a second formatter over the same components. The wording to
review:

> input field 'FilmFilter.categoryRef': `@nodeId(typeName: "Category")` reaches its target through an
> `@reference` path whose last step lands on table 'film_category', not on Category's `@table`
> 'category'. A decoded Category id binds against columns of 'category', so the path has to end there:
> add the remaining step, or name the type the path already reaches.

The LSP reads the same projection off the catalog snapshot, so an edit-time diagnostic should follow
from consuming the verdict rather than needing its own work. Confirm that at pickup.

## Type parity per landed position

`landKeyColumns` matches a key column against the chain's carried arrivals through `indexBySqlName`,
which compares `ColumnRef.sqlName()` with the case folded and consults nothing else. `ColumnRef` also
carries `columnClass`, the column's Java class as jOOQ reports it, and the landing never reads it.
Compare it at the point a position lands, and refuse the leaf when the two disagree.

The remedy is not stated, deliberately, because there are two and graphitron cannot tell which applies.
A divergence can be accidental (one physical column, one of its tables given a converter for an
unrelated reason, where the author wants to align the catalog) or intended (a code table typing its own
key differently from the tables referencing it, where aligning the catalog would be wrong and the
author may need a different reference path instead). A message naming a `forcedType` as *the* fix is
wrong half the time. State the disagreement and let the author choose:

> input field 'DivergedRefChildFilter.orgRef': `@nodeId(typeName: "ConverterOrg")` decodes key column
> 'org_code' of table 'converter_org', which binds as java.lang.String, and the reference path lands it
> on column 'org_code' of table 'diverged_ref_child', which binds as java.lang.Long. The two columns
> disagree on Java type, so no predicate can bind one against the other. Either align the two columns'
> catalog types, or route the reference through a path that lands the key on a column of its own type.

Channel: `landKeyColumns` returns a `PathResolution` (its existing `Walked` / `Refused` pair) rather
than a bare `List<KeyLanding>`. All three of its call sites already produce a `PathResolution`, so this
costs no new type and keeps both checks on one channel.

Scope: the key-to-local comparison, which is the operand pair the emitted predicate actually binds. The
other name match inside `landKeyColumns`, the carry loop's `indexBySqlName` against the next hop's
departing columns, compares the two ends of a declared foreign key, and a type disagreement *there* is
already answered for the join predicate it renders by `ColumnComparison`. Leave it alone.

The read direction of this same directive already makes exactly this comparison, which is the shape
this section extends rather than invents. `FieldBuilder`'s record-read leaf compares a column's
`columnClass` against the encode's key-column class and produces `readEncodeTypeMismatch`, a message
naming both columns and both types and prescribing no remedy. `RoutineDirectiveResolver` does the same
against a routine parameter's declared type. The decode direction is the one with no such check.

## One channel, at the resolver, upstream of every rail

Both refusals are a `Rejection` produced by `NodeIdLeafResolver` at classify time, not by a separate
validator pass. That is where every other `@nodeId` leaf fault already lands (a key wider than `Row22`,
a condition step inside a path, an unresolvable route, participants inferring different node types), so
a new cause arrives as another cause of `Resolved.Rejected`, sharing one message vocabulary and one
diagnostics residue.

It is also the answer to whether the write and `@lookupKey` rails are covered by their own gates. They
are not. Those four rails refuse a `FilterBinding.Remote` carrier through
`FilterBinding.remoteBindingUnsupported`, which catches only the mislandings where *no* position
landed. Every row of the table above except the first lands every position and classifies `DirectFk`,
so a mislanded or type-diverged landing reaches INSERT, UPDATE, DELETE and the `@lookupKey` lookup
today with nothing examining it. One refusal at the resolver covers all of them; a per-rail check would
be four copies of one question, in four differently shaped error channels.

## Tests

Both faults are currently accepted, so start by pinning today's behaviour to confirm the coordinates
above still hold before changing them. This tree moves, and everything below was read at `28cd23d1c`.

Unit tier, `NodeIdLeafResolverTest`, which already asserts the arm choice directly off `resolve`:

- A truncated `@reference` path refuses. Sakila only, no new DDL: `Category` over `category` reached
  from `film` by `[{key: "film_category_film_id_fkey"}]` is the coincidence twin (it lands nothing and
  emits correct SQL today), and from `inventory` by `[{key: "inventory_film_id_fkey"}]` is the
  uncompilable twin (`FILM` has no `CATEGORY_ID`). Both refuse after this item, and the pair is worth
  keeping precisely because their current outcomes differ so widely.
- The same refusal on the `@referenceFor` route, since `Route.ParticipantRoute` is a second call site
  of the same check.
- A diverged landing refuses. The DDL already exists: `converter_org.org_code` is a primary key whose
  jOOQ type is `java.lang.String` (the `org_code_domain` `forcedType` carrying
  `OrgCodeStringConverter`), and `diverged_ref_child.org_code` is a plain `bigint` referencing it, so
  the foreign key's two ends diverge by construction. It needs one `NodeIdFixtureGenerator.METADATA`
  entry making `converter_org` a node keyed on `ORG_CODE`; then `@nodeId(typeName: "ConverterOrg")` on
  a `DivergedRefChild` filter auto-discovers the foreign key, lands `org_code` (String) on `org_code`
  (Long), and reproduces the whole of the second fault with no new tables.
- An unaffected control per fault: the correct two-hop `Category` path and a same-typed landing still
  classify as they do today.

Pipeline tier, one case per fault over the classified model, since the observable moves from a
classified carrier to an unclassified one carrying the message. The refusal texts above are this item's
author-facing surface and are stated in full so their wording is reviewed before it is emitted, not
after.

No execution-tier work. Both checks only ever remove schemas from the accepted set, and the schemas
they remove are the ones that do not compile or are right by accident.

## Out of scope

**Auto-extending a truncated path when the missing hop is unique.** A truncated path is not an
incomplete spelling of one meaning, it is a different filter: graphitron cannot tell "the author forgot
a hop" from "the author meant to filter through the intermediate table". Extending it silently would
also keep the coincidence rows quiet, which is the case this item exists for. It contradicts two
positions the resolver already holds: that a stated route is checked rather than repaired, and that
auto-discovery stops at one hop with disambiguation left to the author.

**The bind asymmetry in `ConditionGlueRenderer.columnCompare`.** Its four branches disagree about
binding: single-column equality emits `DSL.val(value, alias.COL)`, so the value binds at a column's
`DataType` and its converter applies, while single-column membership and both row forms pass the bare
local. That is a live fault of its own, independent of validation and reachable on schemas this item
accepts: a converter-backed key column whose landing agrees in Java type still binds untyped in three
of the four branches, which is the shape the `converter_campus` fixture pins, and PostgreSQL answers a
`bigint` domain compared against a `varchar` bind with "operator does not exist" at runtime. Filed
separately as `roadmap/nodeid-row-predicate-binds-untyped.md`.

**Coercing a divergence rather than refusing it.** See below.

## Other solutions we've considered

**Coerce the type divergence instead of refusing it.** `ColumnComparison` is the tree's single minting
surface for comparing two catalog columns and, by its own javadoc, the one place in the generator that
reconciles a Java type disagreement between them. It does not refuse. It emits
`left.eq(right.coerce(left))`, with a companion bind rule that binds a value at the `DataType` of the
column it was read from. Its soundness argument is that a jOOQ `Converter` is a client-side mapping
only, so two ends of a foreign key are the same SQL type whatever their Java spelling; `coerce`
reinterprets a field's Java type and leaves the rendered SQL alone; and no value is ever parsed or
widened, because the bind runs the converter the catalog already declares. That argument reaches the
landing case too, once the terminal-table check above has established that a landing really is a
declared foreign key's two ends, and the row forms would coerce per cell. It also handles the
divergence a consumer *intends*, which the refusal cannot: for a code table typing its key differently
from the tables referencing it, refusing leaves no legal spelling of a filter that is semantically
fine, and the author's remedies are to change a catalog that is deliberately shaped or to drop the
filter.

The refusal wins on one point, and it is a sequencing point rather than a correctness one. A coercion
makes the catalog's self-disagreement invisible, and the author is the only party who can say whether
it is accidental or intended. A refusal can be relaxed into a coercion later, once a fixture proves the
coerced emission executes; a coercion shipped first means no consumer ever learns their catalog
diverges. The first consumer to hit an intended divergence with no alternative path is the signal to
revisit, and the two answers are compatible rather than rival: the refusal site is exactly where the
coercion would be minted. A reviewer who disagrees with that ordering should say so at the
`Spec → Ready` gate, because it is the one fork in this item whose answer changes what gets built.

**A separate validator pass over the classified model.** Rejected. It would re-derive the route, the
participant in scope, and the per-position landing that `NodeIdLeafResolver.resolve` already holds, and
it would have to reconstruct which of the three routes the leaf took in order to phrase a remedy the
author can act on, since the remedy names the directive the author wrote. It would also answer at a
second altitude a question every rail currently reads off one arm.

## Provenance

A consumer subgraph of roughly 60k lines of SDL over an Oracle catalog, migrating to graphitron 10, got
generated Java that does not compile out of a schema `graphitron:generate` accepted with zero errors.
Both faults above, in one schema. Five sites wrote a `@nodeId(typeName:)` filter whose path stopped one
foreign key short of the node's table, where two of the node's three key columns exist on the terminal
table with an unrelated meaning and the third does not exist at all, so the remote predicate rendered a
column the terminal table does not have. One site landed all nine positions of a composite key by name,
where one position's column carries a converter on the node's own table and not on either consuming
table, emitting a `Row9<..., Short>` compared against a `List<Row9<..., Integer>>`. Six further sites
in the same schema land a wrong terminal table whose columns agree with the node's key in both name and
type, because they are the child columns of that table's own foreign key to the node: those compile and
produce correct SQL, and they are why this item is about verification rather than about a codegen
crash.
