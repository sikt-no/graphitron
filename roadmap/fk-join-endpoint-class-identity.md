---
id: R440
title: "FK-join synthesis must resolve the endpoint (and FK) by jOOQ class identity, not bare name"
status: Backlog
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

## Intent (Spec to settle mechanism)

Resolve the FK endpoint via jOOQ class identity: the `ForeignKey` object already holds its target
`Table`, so use the existing `findTableByClass` (`JooqCatalog.java:159`) on that class instead of
re-looking-up by bare name. Make `findForeignKey` / `findForeignKeyByName` schema-scoped: scope by
the already-resolved source/target table class, or reject as ambiguous when a name matches in >1
schema, rather than the current silent first-hit. Fix (or drop) the misleading
`findForeignKeyByName` docstring to match whatever scoping is chosen. Leave orientation
(`foreignKeyOnSource`) alone; it is already identity-based.

## Shape / cross-links

- Same "compare class identity, not the name string" pattern as **R396** (`@reference` FK
  source-side predicate, Done) and **R422** `reference-terminal-verdict-return-type-identity`
  (return-type-side verdict, Backlog). This is the FK-join-endpoint sibling of that pair.
- **Depends on R438** `materialize-joinpath-facts` (In Review at time of filing): R438 is reworking
  `JoinStep` / join-path facts in the same `synthesizeFkJoin` territory, so this should land on top
  of R438's materialized shape rather than race it. Re-confirm R438's status when this item is
  picked up; if R438 has since landed, the dependency is discharged and this becomes a
  straightforward follow-up.

## Test gap

`JooqCatalogMultiSchemaTest` and `SynthesizeFkJoinReorderedKeysTest` cover cross-schema FKs only
with **unique** table and FK names. Neither covers a colliding bare table name reached via FK
synthesis, nor a colliding FK *constraint* name across schemas. Add a fixture of two schemas each
carrying tables `note` and `event` and an identical FK `note_event_fk` in both, and add cases for
all three sub-paths above (endpoint resolution, FK resolution, and both `NodeIdLeafResolver`
entry points).
