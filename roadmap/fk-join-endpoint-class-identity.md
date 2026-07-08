---
id: R440
title: "FK-join synthesis must resolve the endpoint (and FK) by jOOQ class identity, not bare name"
status: Spec
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# FK-join synthesis must resolve the endpoint (and FK) by jOOQ class identity, not bare name

## Problem

FK-join synthesis resolves an FK's endpoint (and the FK itself) by **bare SQL name** even
though it already holds the jOOQ `ForeignKey` object, which knows its exact target `Table`
class. With two schemas that share a bare table name, the bare-name lookup returns
`TableResolution.Ambiguous` and the join fails; with a colliding FK *constraint* name across
schemas the bare-name lookup silently returns the first match, a wrong-join hazard rather than
a rejection. This is the FK-join-endpoint sibling of the same "compare class identity, not the
name string" bug fixed on the source side in R396 (Done) and pending on the return-type side in
R422 (Backlog): the orientation of the join is now decided by the R396 class-identity primitive
(`foreignKeyOnSource`), but the endpoint the orientation points at is then re-looked-up by bare
name, reintroducing exactly the ambiguity R396 removed.

Verified in current trunk:

1. **Endpoint re-resolved by bare name.** `BuildContext.synthesizeFkJoin`
   (`BuildContext.java:1356`) computes `targetSqlName` from the bare jOOQ names
   `f.getTable().getName()` / `f.getKey().getTable().getName()` (L1358-1361) and resolves the
   endpoint via `catalog.findTable(targetSqlName)` (L1368). Orientation itself is already correct
   (L1360 uses `foreignKeyOnSource`), but the target table is then found by bare name, so two
   schemas sharing that bare table name yield `Ambiguous` and the join fails, even though `f`
   already holds the exact target `Table` class. The same bare re-lookup happens for the FK at
   L1363 (`findForeignKeyByName(f.getName())`).

2. **FK lookups are not schema-scoped.** `JooqCatalog.findForeignKey` (`JooqCatalog.java:397`)
   and `findForeignKeyByName` (`JooqCatalog.java:443`) both stream *every* schema and
   `.findFirst()` on a case-insensitive SQL-name match. When an FK constraint *name* collides
   across schemas this returns the first hit silently, a wrong-join hazard, not a rejection. The
   `findForeignKeyByName` docstring (L439-441) claims "FK names are scoped to a `Keys` class so
   cross-schema ambiguity does not apply", but the implementation does not perform that scoping.

3. **`NodeIdLeafResolver` reaches this two ways.** A `@nodeId` input field *with* a `@reference`
   path routes `parsePath` → `synthesizeFkJoin`; *without* one it routes
   `findUniqueFkToTable`. The latter's `findForeignKeysBetweenTables` (`JooqCatalog.java:535`)
   already resolves endpoints by **class identity** (good), but `NodeIdLeafResolver`
   (`NodeIdLeafResolver.java:463`) then discards that and re-looks-up the FK by bare name via
   `findForeignKey(fkName)`, reintroducing the collision before handing back to
   `synthesizeFkJoin`.

## Design

Four moves, all "decide once, carry the decision as a type". Orientation (`foreignKeyOnSource`)
stays untouched; it is already identity-based.

**Scope note (extends the filed intent):** the plan resolves *both* endpoints of the join by class,
not just the target. The origin has the same bug (a source whose bare name collides across schemas
hits `TableResolution.Ambiguous` at the `findTable(sourceSqlName)` re-lookup even though the FK pins
the exact table), and fixing only the target would leave `synthesizeFkJoin` half-converted. The
diagnostics consequence is handled explicitly in D2 and pinned by tests.

### D1: identity-based FK-ref primitive

New `JooqCatalog.findForeignKeyRef(ForeignKey<?, ?> fk)` returning `ForeignKeyResolution`. It scans
only the `Keys` class of the FK-holder schema (the schema of `fk.getTable()`; the FK-child endpoint
class pins the owning schema structurally, so schema-scoping does not rest on a cross-schema scan)
and matches the constant whose *value* is the given instance, by reference identity. jOOQ's
generated `getReferences()` returns the same `Keys.FK_*` singletons, so identity holds for every FK
that flows out of the catalog; that singleton assumption is an invariant, and the identity-ref unit
test below is its named enforcer (the method doc names the test). No name matching anywhere.
`NotInCatalog` when the constant is absent (catalog-vs-FK mismatch, defensive).

Callers switched to it:

- `synthesizeFkJoin` (`BuildContext.java:1363`), replacing `findForeignKeyByName(f.getName())`.
- `fkJavaConstantName`, retargeted to take the `ForeignKey` object; both callers
  (`BuildContext.java:964`, `CatalogBuilder.java:1139`) already hold it and currently round-trip
  through `fk.getName()`.

With those gone, `findForeignKeyByName(String)` has no production caller left; delete it (its
false "FK names are scoped to a `Keys` class so cross-schema ambiguity does not apply" docstring
dies with it) and migrate its direct tests to `findForeignKeyRef`. `ForeignKeyResolution` itself
keeps its two arms; identity lookup cannot be ambiguous.

### D2: both endpoints by class in `synthesizeFkJoin`

Target entry resolves via `findTableByClass` on
`(fkOnSource ? f.getKey().getTable() : f.getTable()).getClass()`; origin likewise on the opposite
endpoint. `sourceSqlName` remains only as input to orientation and as the requested-name argument
to `toTableRef` on the origin (the target's requested name is the endpoint's own `getName()`).

Diagnostics: the `UnknownTable` arm stays as a typed rejection but becomes defensive-only, firing
when `findTableByClass` misses (catalog mismatch), never on bare-name ambiguity. The current
fabricated-source case in `JooqCatalogMultiSchemaTest.synthesizeFkJoin_unknownEndpointTableSurfacesUnknownTable`
is retired in favour of the upstream membership checks, and the claim "callers validate source
membership upstream" gets enforcers: a test per entry point that a source not touching the FK
rejects (the `foreignKeyTouchesTable` check at `BuildContext.java:1559` for `{key:}`; construction
guarantees for the shim and `NodeIdLeafResolver` routes, pinned at whatever seam the existing
coverage sits). The defensive arm must stay a typed rejection, not a throw or silent success.

### D3: one scoped, sealed name lookup

`findForeignKey(String)` returns `Optional`, so an ambiguity outcome can only collapse into
"not found", erasing exactly the hazard this item exists to surface. Replace it (single primitive,
no scoped/unscoped overload pair) with a lookup that takes the source scope and returns a
`JooqCatalog`-local sealed result:

```java
sealed interface ForeignKeyLookup {
    record Resolved(ForeignKey<?, ?> fk) implements ForeignKeyLookup {}
    record NotInCatalog() implements ForeignKeyLookup {}
    record Ambiguous(List<String> schemas) implements ForeignKeyLookup {}
}
ForeignKeyLookup findForeignKey(String name, @Nullable String sourceSqlName)
```

The raw `ForeignKey` stays in a `JooqCatalog`-local type (permitted holder), not in
`model.ForeignKeyRef`. Matching keeps today's dual namespace (SQL constraint name, then jOOQ
Java-constant name, both case-insensitive). When `sourceSqlName` resolves to a table class,
candidates are filtered to FKs touching that table (class identity via the existing
`endpointMatches` contract), so a cross-schema constraint-name collision disambiguates naturally.
`Ambiguous` fires when more than one distinct FK still matches: scope null (the `{key:}` path can
have `currentSourceSqlName == null`), scope unresolvable, or a genuine residual collision. Silent
first-hit is gone.

Author-facing call sites and their `Ambiguous` handling:

- `parsePathElement` `{key:}` (`BuildContext.java:1549`), scope `currentSourceSqlName`;
- the ID-reference synthesis shim (`BuildContext.java:2190`, `:2212`), scope `tableName` (the
  qualifier map the name came from is built per-table, so the global re-lookup today can cross
  schemas);
- `resolveRecordFkTargetColumns` explicit `@reference(key:)` (`BuildContext.java:2650`), scope
  `recordTable.tableName()`.

One further, non-author-facing caller must migrate with the signature change but keeps its
`Optional` contract rather than surfacing `Ambiguous`: `qualifierForFk` (`JooqCatalog.java:616`)
calls `findForeignKey(fkName)` unscoped and then filters by `foreignKeyOnSource(sourceTableSqlName)`,
so a colliding FK name today resolves the wrong schema's FK and collapses to empty, tripping the
`orElseThrow("should be unreachable")` at the shim's `BuildContext.java:2186-2189`. Pass
`sourceTableSqlName` as the scope; `Resolved` maps to the qualifier, `NotInCatalog`/`Ambiguous`
both map to `Optional.empty()` (scope makes the collision resolve, so the `orElseThrow` stays a
genuine can't-happen guard). The shim's author-facing ambiguity rejection is already covered by the
`:2190`/`:2212` sites above; `qualifierForFk` does not duplicate it.

Each rejects `Ambiguous` through a new arm of the FK rejection surface: extend
`unknownForeignKeyRejection` (or a sibling builder) with an ambiguous variant producing an
`AuthorError.Structural` rejection naming the colliding schemas and the qualified guidance,
symmetric to `unknownTableRejection`'s ambiguity arm. `FkJoinResolution.UnknownForeignKey` keeps
its `String` payload: after D1 the lookup inside `synthesizeFkJoin` is identity-based, so
`Ambiguous` is unreachable there; ambiguity is rejected at the three author-facing call sites
above, before `synthesizeFkJoin` is entered.

### D4: `findUniqueFkToTable` returns the object it already found

Change `findUniqueFkToTable` to return `Optional<ForeignKey<?, ?>>` instead of the bare constraint
name. `NodeIdLeafResolver.resolveFkJoinPath` (`NodeIdLeafResolver.java:455-472`) passes the object
straight to `synthesizeFkJoin`, deleting the name round-trip at `:463` that reintroduced the
collision after `findForeignKeysBetweenTables` had already resolved endpoints by class. The only
other caller (`BuildContext.java:2199`) tests presence only. Update the deduction docstring at
`BuildContext.java:2632-2636`.

## Implementation notes

- `JooqCatalog.java`: D1 primitive, D3 sealed lookup + deletion of the `Optional` form and of
  `findForeignKeyByName`, D4 signature change; fix the stale cross-reference in the
  `ForeignKeyResolution` doc block (around `JooqCatalog.java:1315-1319`).
- `BuildContext.java`: `synthesizeFkJoin` endpoint/FK resolution (D1, D2), the three D3 call sites,
  `fkJavaConstantName` retarget, ambiguous-FK rejection builder.
- `NodeIdLeafResolver.java`: D4 consumption.
- `CatalogBuilder.java:1139`: `fkJavaConstantName` retarget.
- `model/ForeignKeyRef.java`: doc pointer update (built by `findForeignKeyRef` now).
- The new `ForeignKeyLookup` is a `JooqCatalog`-local result type in the same family as
  `TableResolution` / `ForeignKeyResolution` / `RoutineResolution`: documented by rich javadoc on
  the sealed interface and its arms, pinned by no doc-coverage meta-test. It is *not* in scope for
  `VariantCoverageTest` (which covers `GraphitronField` / `GraphitronType` classification leaves) or
  `SealedHierarchyDocCoverageTest` (which walks only the `Rejection` hierarchy against
  `typed-rejection.adoc`). The ambiguous-FK rejection arm produces an existing `Rejection.structural`
  leaf (as the table-ambiguity arm at `BuildContext.java:1112` does), so it adds no new `Rejection`
  permit and no `typed-rejection.adoc` obligation. Match the sibling result types' javadoc
  convention; there are no doc entries to add.
- Passing raw `ForeignKey` through `BuildContext`/`NodeIdLeafResolver` is not a new containment
  leak; both are in the deliberate jOOQ boundary set and `synthesizeFkJoin` already takes one.

## Tests

Fixture: extend the multischema block in `graphitron-sakila-db/src/main/resources/init.sql` with a
`note` table in *both* schemas, each carrying an FK explicitly named `note_event_fk`
(`CONSTRAINT note_event_fk ... REFERENCES <own schema>.event`). That yields both collisions the
current fixture lacks: a colliding bare *table* name (`event`) reached via FK synthesis, and a
colliding FK *constraint* name across schemas. Postgres constraint names are schema-scoped, so the
duplicate name is legal. Seed one row per new table so execution-tier reuse stays possible.

Unit tier (`JooqCatalogMultiSchemaTest` unless noted):

- **D1:** `findForeignKeyRef` on each schema's `note_event_fk` instance returns that schema's
  `Keys` class and constant; this is the named enforcer of the FK-singleton invariant. A foreign
  (non-catalog) FK instance yields `NotInCatalog`.
- **D2:** `synthesizeFkJoin` over `multischema_a`'s `note_event_fk` resolves: target `TableRef` is
  schema A's `event` by class (today: `Ambiguous` failure); same from the B side lands on B's
  `event`. Origin-side: a schema-colliding source name also resolves. Membership enforcers per D2.
- **D3:** scoped lookup with `source = "multischema_a.note"` resolves to A's FK; `null` scope with
  the colliding name yields `Ambiguous` naming both schemas; the rejection builder's ambiguous arm
  produces the structural prose (schema list + qualified forms).
- **D4:** `findUniqueFkToTable` returns the FK object; directionality cases in
  `JooqCatalogIdRefTest` migrate to the new shape.

Resolver tier: cover both `NodeIdLeafResolver` entry points (`@reference` path via
`parsePath`/`synthesizeFkJoin`, and auto-discovery via `findUniqueFkToTable`) against the colliding
fixture, at the seam the existing `NodeIdLeafResolverTest` / `NodeIdPipelineTest` coverage sits;
the implementer picks the exact fixture wiring (the nodeid fixtures are single-schema today, so
the catalog-level unit cases above carry the collision coverage if resolver-tier wiring proves
disproportionate; note the choice in the item when made).

## Shape / cross-links

- Same "compare class identity, not the name string" pattern as **R396** (`@reference` FK
  source-side predicate, Done) and **R422** `reference-terminal-verdict-return-type-identity`
  (return-type-side verdict, Backlog). This is the FK-join-endpoint sibling of that pair. Two more
  family members filed since: **R441** (typed-accessor match) and **R442** (condition-method table
  param); their surfaces are disjoint from this item's (FK endpoint + FK-name lookups only).
- **R438** `materialize-joinpath-facts` reached Done while this Spec was being drafted, so the
  filed dependency is discharged (front-matter already cleared). The plan above is written against
  R438's landed `JoinStep.Hop` shape on trunk; the `synthesizeFkJoin` line references reflect it.
- Original test-gap observation (now covered by the Tests section above):
  `JooqCatalogMultiSchemaTest` and `SynthesizeFkJoinReorderedKeysTest` cover cross-schema FKs only
  with unique table and FK names; neither a colliding bare table name reached via FK synthesis nor
  a colliding FK constraint name across schemas.
