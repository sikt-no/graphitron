---
id: R435
title: "Routine table nodes: order-significant @routine / @reference composition"
status: Backlog
bucket: feature
theme: service
depends-on: []
created: 2026-07-05
last-updated: 2026-07-05
---

# Routine table nodes: order-significant @routine / @reference composition

A jOOQ table-valued function is a table *node* (R333's `tableExpr` `RoutineCall`) that can supply
the **source** of a field's rows, the **target** the field projects, or sit **between** tables in
the join chain. The shipped `@routine` (R300) exposes only one of these shapes: a root field whose
projected table *is* the routine result, with no composition. R333's join-path resolution names this
gap explicitly ("the existing `@routine` code is broken not because the routine result is projected,
but because that is the *only* shape it allows") and defers the SDL surface. This item is that
surface, plus the model placements it needs.

## Design (settled 2026-07-05)

**The rule.** A field's table chain is the concatenation, in written order, of the implicit head
(the parent's table, at child positions) and each directive application's contribution; the last
node must equal the field's `@table` type (or the routine's result table when a routine is last).
Directive order is semantically significant, which the GraphQL spec sanctions ("the order in which
directives appear may be significant") and graphql-java preserves. `@routine` contributes a routine
node; `@reference(path:)` contributes FK hops; both become `repeatable`.

Every position falls out of the one rule, with no fixed orientation baked into either directive:

```graphql
tilganger:    [Tilgang!] @routine(...)                                     # root: start == terminus (R300, unchanged)
recentFilms:  [Film!]    @routine(...) @reference(path: [{table: "film"}]) # routine then hops: routine as source
filmsForUser: [Film!]    @routine(name: "films_for_actor",
                                  columnMapping: "pActorId: actor_id")     # child: correlated single node (lateral)
deepRows:     [RtnRow!]  @reference(path: [...]) @routine(...)             # hops then routine: routine as terminus
sandwich:     [Film!]    @reference(...) @routine(...) @reference(...)     # routine strictly between tables
```

**What stays untouched.** `ReferenceElement` gains no arm; `@reference` carries no new luggage. The
composition surface is directive co-occurrence plus order. Field-level `@routine` alone remains
R300's shipped root slice and desugars at the parse boundary into the single-node chain, so there is
exactly one resolved shape downstream.

**Model placements** (settled with an architecture consult; keep these where they landed):

* Correlated routine parameters (fed by parent / previous-node columns rather than GraphQL
  arguments) are authored via a new `columnMapping` slot parallel to `argMapping`; explicitly two
  slots, no name-inference (inference would let adding a GraphQL argument silently flip a binding
  from correlated to constant). The binding lands as a new `ParamSource` arm, the column-granularity
  sibling of `ParamSource.SourceTable`, not a bespoke sum on `RoutineRef.ArgBinding`; one call-source
  taxonomy for service / condition / tableMethod / routine calls.
* Lateralness (the SQL is `CROSS JOIN LATERAL routine(prev.col)`; jOOQ: `DSL.lateral()` and the
  generated TVF table's `Field`-overload call surface, which `JooqCatalog.resolveTableValuedFunction`
  currently skips) is a **positive third arm on the join step's `on` axis**
  (`ColumnPairs | Predicate | Lateral`), never an overloaded absence; R333's invariant
  "`on` absent iff start node" survives.
* Hops adjacent to a routine node are FK-less and key by R333's rules: name-matched target UK
  (PK default) with the result-columns-expose-key-columns-by-name build check, or a `condition:`.
* The wrapper stays author-declared (jOOQ exposes no TVF row-cardinality; same stance as R300).

**Validation** (all typed rejections; the validator owns misordering since order now carries
meaning):

* Root fields: the first directive application must supply the head, i.e. be `@routine`. This
  generalizes and *checks* R333's root-iff-routine invariant, which today rests on omission.
* Terminus invariant: last node's table class == field's `@table` type (== routine result table when
  a routine is last). Exists at root today; extends to every chain.
* `columnMapping` legal iff a previous node exists; bound columns must exist on the previous node
  with types compatible with the routine parameters.
* `repeatable` applies semantically only where order-composition does (FIELD_DEFINITION table
  chains); GraphQL cannot scope repeatability per location, so the classifier rejects repeats at
  other locations.

## Relationship to other items

* **R333** is the substrate (the `tableExpr` fact, the linearized join path, the FK-less keying
  rules). This item answers R333's deferred "`@oneOf` SDL surface for the path element" residue with
  *no new element surface at all*: composition is directive order. It also discharges R333's open
  residue (b), the explicit root-entry validator.
* **R300** named these deferred follow-ups: child-positioned `@routine` and heterogeneous binding
  sources land here; procedure-write and scalar-read routines remain out of scope and separate.
* **R403** (`@tableMethod` rethink): a child routine node rides the parent's query as a join, so it
  has no N+1, unlike `ChildField.TableMethodField`'s per-row synchronous fetcher (R288). That
  narrows `@tableMethod`'s residual unique capability to request-time table *choice*. Note for the
  rethink, not scope here. `@tableMethod` currently composes with `@reference` in a fixed
  orientation (method as terminus); under order-significance that asymmetry dissolves if
  `@tableMethod` later adopts the same rule.
* **R381** (LSP path authoring): the "implied head" readability gripe at child positions is a
  display concern, not an authoring gap (the head cannot be anything but the parent's table); an
  inlay hint rendering the resolved head belongs on R381's stepper substrate, noted there, not here.

## Out of scope

* Procedure-write and scalar-returning routines (different `operation` / `target` story; R300's
  other deferred forks).
* Emitting jOOQ's generated navigation methods (`FILM.language()`) for FK hops: the authoring
  surface keys on catalog identities (FK constraint names), not the Java-surface method names, which
  are collision-prone and consumer-flag-gated (`graphitron-sakila-db/pom.xml` disables
  `implicitJoinPathsToMany` for exactly this reason). Emission stays `.onKey(FK)`; native path-join
  rendering is a separate emitter question if ever.
* The legacy `procedureCall*` rejection-fixture translation (named by R300's retirement notes).
