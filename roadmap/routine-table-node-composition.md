---
id: R435
title: "Routine table nodes: order-significant @routine / @reference composition"
status: Backlog
bucket: feature
theme: service
depends-on: [coordinate-lowers-to-datafetcher-queryparts, materialize-joinpath-facts]
created: 2026-07-05
last-updated: 2026-07-06
---

# Routine table nodes: order-significant @routine / @reference composition

A jOOQ table-valued function is a table *node* (R333's `tableExpr` `RoutineCall`) that can supply
the **source** of a field's rows, the **target** the field projects, or sit **between** tables in
the join chain. The shipped `@routine` (R300) exposes only one of these shapes: a root field whose
projected table *is* the routine result, with no composition. R333 names this gap explicitly and
defers the SDL surface. This item is that surface, plus the model placements it needs.

## The rule

A field's table chain is the concatenation, in written order, of the implicit head (the parent's
table, at child positions) and each directive application's contribution; the last node must equal
the field's `@table` type (or the routine's result table when a routine is last). Directive order
is semantically significant, which the GraphQL spec sanctions ("the order in which directives
appear may be significant") and graphql-java preserves. `@routine` contributes a routine node;
`@reference(path:)` contributes FK hops; both become `repeatable`.

Every position falls out of the one rule, with no fixed orientation baked into either directive:

```graphql
tilganger:    [Tilgang!] @routine(...)                                     # root: start == terminus (R300, unchanged)
recentFilms:  [Film!]    @routine(...) @reference(path: [{table: "film"}]) # routine then hops: routine as source
filmsForUser: [Film!]    @routine(name: "films_for_actor",
                                  columnMapping: "pActorId: actor_id")     # child: correlated single node (lateral)
deepRows:     [RtnRow!]  @reference(path: [...]) @routine(...)             # hops then routine: routine as terminus
sandwich:     [Film!]    @reference(...) @routine(...) @reference(...)     # routine strictly between tables
```

`ReferenceElement` gains no arm; `@reference` carries no new luggage. The composition surface is
directive co-occurrence plus order. Field-level `@routine` alone remains R300's shipped root slice
and desugars at the parse boundary into the single-node chain, so there is exactly one resolved
shape downstream.

## Semantics

* **Correlated parameters.** A routine parameter fed by a parent / previous-node column (rather
  than a GraphQL argument) is authored via a new `columnMapping` slot parallel to `argMapping`;
  explicitly two slots, no name-inference. A column-bound parameter makes the call correlated: the
  SQL is `CROSS JOIN LATERAL routine(prev.col)` (jOOQ: `DSL.lateral()` and the generated TVF
  table's `Field`-overload call surface, which `JooqCatalog.resolveTableValuedFunction` currently
  skips).
* **Keying at FK-less nodes.** A routine's result table carries no FK metadata, so hops adjacent
  to a routine node key by R333's rules: name-matched target UK (PK default) with the
  result-columns-expose-key-columns-by-name build check, or a `condition:`.
* **Cardinality.** The wrapper stays author-declared; jOOQ exposes no TVF row-cardinality (same
  stance as R300).

## Substrate and sequencing

The model placements below target R333's join-path facts, **not** the shipped model: the live
`JoinStep` is the flat `FkJoin | ConditionJoin | LiftedHop` seal with no `on` axis and no
`tableExpr`, and root `@routine` is the sealed `QueryField.QueryRoutineTableField` leaf. R438
(`materialize-joinpath-facts`) is the substrate slice that reshapes `JoinStep` to
`(tableExpr target, on)`; this item then adds the `RoutineCall` target arm and the `Lateral`
`on`-arm, and retires `QueryRoutineTableField` by re-homing the root slice onto the single-node
chain, at which point (and not before) the "exactly one resolved shape downstream" claim in *The
rule* holds. R314's reentry emit re-platform is the parallel source-side thread; no dependency
either way, but the emit conventions should be coordinated.

## Model placements

* The column binding lands as a new `ParamSource` arm, the column-granularity sibling of
  `ParamSource.SourceTable`: one call-source taxonomy for service / condition / tableMethod /
  routine calls. `RoutineRef.ArgBinding` keeps only the routine-specific envelope (parameter name,
  boxed type) around the shared source arm.
* Lateralness is a positive third arm on the join step's `on` axis
  (`ColumnPairs | Predicate | Lateral`); R333's invariant "`on` absent iff start node" survives.

## Validation

All typed rejections; the validator owns misordering since order now carries meaning.

* Root fields: the first directive application must supply the head, i.e. be `@routine`. This
  generalizes and *checks* R333's root-iff-routine invariant, which today rests on omission.
* Terminus invariant: last node's table class == field's `@table` type (== routine result table
  when a routine is last). Exists at root today; extends to every chain.
* `columnMapping` legal iff a previous node exists; bound columns must exist on the previous node
  with types compatible with the routine parameters.
* `repeatable` applies semantically only where order-composition does (FIELD_DEFINITION table
  chains); GraphQL cannot scope repeatability per location, so the classifier rejects repeats at
  other locations.

## Out of scope

* Procedure-write and scalar-returning routines (different `operation` / `target` story; R300's
  other deferred forks).
* The legacy `procedureCall*` rejection-fixture translation (named by R300's retirement notes).

## Relationship to other items

* **R333** is the substrate (the `tableExpr` fact, the linearized join path, the FK-less keying
  rules). This item answers R333's deferred "`@oneOf` SDL surface for the path element" residue
  with *no new element surface at all*: composition is directive order. It also discharges R333's
  open residue (b), the explicit root-entry validator.
* **R300** named these deferred follow-ups: child-positioned `@routine` and heterogeneous binding
  sources land here; procedure-write and scalar-read routines remain out of scope and separate.
* **R403** (`@tableMethod` rethink): a child routine node rides the parent's query as a join, so
  it has no N+1, unlike `ChildField.TableMethodField`'s per-row synchronous fetcher (R288). That
  narrows `@tableMethod`'s residual unique capability to request-time table *choice*. Note for the
  rethink, not scope here. Under order-significance, `@tableMethod`'s fixed composition
  orientation (method as terminus) dissolves if it later adopts the same rule.
* **R381** (LSP path authoring): the "implied head" readability gripe at child positions is a
  display concern, not an authoring gap (the head cannot be anything but the parent's table); an
  inlay hint rendering the resolved head belongs on R381's stepper substrate, noted there, not
  here.

## Design lineage: rejected alternatives (2026-07-05 session)

Recorded so the surface is not re-litigated; each attempt failed by inventing surface for
information that already had a home, and each left something the final design keeps.

1. **Extend `ReferenceElement` with a `routine` arm.** Rejected: a fourth slot on an input most
   callsites fill two-of-four on (the input-wrapper-widening smell), with a two-axis exactly-one-of
   (target `table` xor `routine`; on `key` xor `condition`) GraphQL `@oneOf` cannot express.
   Salvaged from the architecture consult on this draft: the `ParamSource` placement (not a bespoke
   sum on `RoutineRef.ArgBinding`) and lateralness as a positive fact (never an overloaded
   `on`-absence), both kept above.
2. **A new slim path directive** (`@join` / `@via` / `@from(table:, join:)`) keyed on a compact
   path grammar. Rejected: every candidate name either overclaims (the field's type only projects
   the terminus, so `@join` suggests a join product the type never sees) or re-derives `@reference`
   minus two slots. Salvaged: the authoring surface keys on catalog identities (FK constraint
   names), never on jOOQ's generated navigation methods, which are collision-prone and
   consumer-flag-gated (`graphitron-sakila-db/pom.xml` disables `implicitJoinPathsToMany` for
   exactly this reason); emission stays `.onKey(FK)`, native path-join rendering a separate emitter
   question if ever.
3. **`@reference(from:)`.** Rejected by its own logic: an argument only legal at root is a
   directive wearing an argument costume, and child fields already have a head that cannot be
   overridden. Salvaged: the implied-head readability concern is a display problem (the R381 inlay
   hint), and root-entry visibility is already authored (the routine is written at the field).
4. **A fixed composition rule** (`@routine` always supplies the head, `@reference` always builds on
   it). Superseded by order-significance, which recovers everything the fixed rule deferred
   (routine as terminus after hops, routine strictly between tables, multiple routines) with zero
   additional surface.
