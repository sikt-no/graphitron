---
id: R435
title: "Routine table nodes: order-significant @routine / @reference composition"
status: Ready
bucket: feature
theme: service
depends-on: [coordinate-lowers-to-datafetcher-queryparts]
created: 2026-07-05
last-updated: 2026-07-07
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
* **Child fetch form.** A routine-backed child field rides the batched keyed re-query (the
  `VALUES(idx, key...)` key set with the routine joined `LATERAL` over the lifted parent columns)
  or the inline parent join; **never** a per-row synchronous fetcher. This is the R288 constraint
  that `ChildField.TableMethodField` violates; the spec pins the form, the emitter choice between
  inline and batched follows the ordinary `@splitQuery` / arrival rules.
* **Order contract.** Graphitron consumes the authored SDL, where graphql-java preserves directive
  application order. Tooling that rewrites SDL and reorders repeatable directive applications
  (schema printers, federation composition pipelines) is out of contract and documented as such:
  the terminus and keying checks catch most reorders, but two orderings can both terminate
  correctly and mean different join graphs (the `sandwich` case), so order-preserving round-trips
  are a stated requirement, not an assumption. The editor surfaces the resolved chain (the R381
  inlay/hover) so the load-bearing order is always visible while authoring. Enforced by: the
  root-head, terminus, and keying rules for any reorder that changes the head or terminus or
  breaks keying; nothing at build time for terminus-preserving reorders (the `sandwich` case),
  which remain an external round-trip contract on SDL tooling; the R381 inlay/hover is
  authoring-time visibility, not an enforcer.

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
  routine calls. Concretely: `RoutineRef.ArgBinding` becomes
  `(routineParamName, paramType, ParamSource)`, the existing argument case routes through
  `ParamSource.Arg` (not a bare `String`), and the new column arm carries the correlated case, so a
  routine parameter has exactly one source shape. `ParamSource`'s javadoc, today scoped to "a
  single parameter in a `MethodRef`", widens to cover `RoutineRef`.
* Lateralness is a positive third arm on the join step's `on` axis
  (`ColumnPairs | Predicate | Lateral`); R333's invariant "`on` absent iff start node" survives.

## Validation

All typed rejections, produced once. The classifier parses the ordered directive applications a
single time, lands the chain, and mints every misordering, terminus, and `columnMapping` violation
below as a typed `Rejection` at classify time; the validator projects those rejections (validate
is a typed-rejection projection of classify, per `typed-rejection.adoc`) and never re-reads
directive order. The head and terminus rules are facts about the landed chain, not the raw
directive list, so no second pass parses order and there is nothing for two passes to disagree
about. Every rule reuses an existing `Rejection` arm; no new arm is minted.

* Root fields: the first directive application must supply the head, i.e. be `@routine`. This
  generalizes and *checks* R333's root-iff-routine invariant, which today rests on omission.
  Arm: `AuthorError.Structural`.
* Terminus invariant: last node's table class == field's `@table` type (== routine result table
  when a routine is last). Exists at root today; extends to every chain. Arm:
  `AuthorError.Structural`.
* `columnMapping` legal iff a previous node exists; bound columns must exist on the previous node
  with types compatible with the routine parameters. Arms: no previous node and type
  incompatibility are `AuthorError.Structural`; a bound column absent from the previous node is
  `AuthorError.UnknownName` with the previous node's columns as candidates (the standard
  candidate-hint contract).
* `repeatable` applies semantically only where order-composition does (FIELD_DEFINITION table
  chains); GraphQL cannot scope repeatability per location, so the classifier rejects repeated
  `@reference` on `ARGUMENT_DEFINITION` and `INPUT_FIELD_DEFINITION` with a message pointing at
  the field-level composition surface (the `@lookupKey` declared-but-rejected precedent). Arm:
  `InvalidSchema.DirectiveConflict` (its "rejected because of where it appears" case).

## Composition with other field surfaces

Day-one verdicts for the directives that could co-occur on a routine-backed field; each rejection
names its `Rejection` arm, is produced at classify time, and lands with a validator projection and
a fixture. Like the validation rules above, every verdict reuses an existing arm.

* **`@splitQuery`**: composes. It forces the new-query anchor, which is the same batched keyed
  re-query form the child fetch already rides; no special casing.
* **`@asConnection` / pagination**: two verdicts, two arms. A chain whose *terminus* is the
  routine result rejects as `InvalidSchema.DirectiveConflict` (R300 already rejects Connection at
  root): keyset pagination needs an ordering contract the FK-less routine result does not carry,
  so the combination can never work. A chain that merely *contains* a routine node but terminates
  on a catalog table rejects as `Deferred`: it classifies cleanly and could support pagination
  later (`planSlug` stays empty until someone files that follow-up item).
* **`@orderBy` / `@condition`**: rejected on routine-backed fields day one as `Deferred`
  (`planSlug` empty, same follow-up story), extending R300's no-field-level-filter-surface stance
  from the root slice to every chain position. Both key on `resolvedTable` (R333) and are
  meaningful for catalog-terminus chains, so this is a capability gap, not a schema contradiction.

## Implementation

* `directives.graphqls`: `repeatable` on `@reference` and `@routine`; `columnMapping: String` on
  `@routine`.
* `RoutineRef.ArgBinding` gains the `ParamSource` slot; `ParamSource` gains the column arm; the
  existing arg case migrates to `ParamSource.Arg` (see *Model placements*).
* `JooqCatalog.resolveTableValuedFunction`: additionally resolve the `Field`-overload call surface
  (today filtered out) for correlated emission.
* Classifier (`FieldBuilder` + `RoutineDirectiveResolver`): read the ordered directive
  applications off the field definition (the only pass that does), build the chain (implicit head
  at child positions), desugar the single-application case, mint the `RoutineCall` target arm and
  `Lateral` `on`-arm onto R438's two-axis `JoinStep`, and produce the chain rejections named in
  *Validation*.
* `GraphitronSchemaValidator`: surfaces the four validation rules plus the composition verdicts
  above by projecting the classifier's typed rejections; no second parse of directive order.
* Emitters: chain rendering in the fetcher generators (root and child), `DSL.lateral()` for
  correlated calls, name-matched keying for hops out of a routine.
* Docs: `routine.adoc` rewrite and the `@reference` page's composition section (see the draft
  below); the order contract documented where repeatable directives are introduced.

## Tests

Pipeline tier is primary; no code-string assertions at any tier.

* **Pipeline (SDL to model to TypeSpec)**: one fixture per chain shape in *The rule*'s example
  block (root single-node, root routine-then-hops, child correlated single-node, child
  hops-then-routine, sandwich), plus `ClassifiedCorpus` entries asserting the classification
  facts.
* **Execution (PostgreSQL)**: a correlated child (`films_for_actor(actor_id)`-shaped fixture
  function added to `init.sql`) verifying the LATERAL correlation returns per-parent rows under
  batching; a root routine-then-hops chain verifying the hop out of the routine keys correctly;
  selection narrowing on a routine-result table (extends `RoutineFieldExecutionTest`).
* **Rejection fixtures**, one per validator rule: root chain not starting with `@routine`;
  terminus mismatch; `columnMapping` with no previous node; `columnMapping` naming a column absent
  from the previous node; name-match keying failure (routine result lacking the key columns);
  repeated `@reference` on `ARGUMENT_DEFINITION` / `INPUT_FIELD_DEFINITION`; `@asConnection`,
  `@orderBy`, and `@condition` on a routine-backed field.

## User documentation (first-client check)

Draft for the directive reference / how-to; moves to its real home when the feature ships.

> *Backing a field with a database function.* A table-valued function in your database appears in
> the jOOQ catalog like any table. To back a field with one, add `@routine(name: "...")`: the
> field's type maps to the function's result table, and the function's parameters are filled from
> the field's GraphQL arguments (`argMapping`) or from columns of the enclosing type's row
> (`columnMapping`). To reach other tables from the function's result, or to reach the function
> through other tables, combine it with `@reference`: the directives on a field, read left to
> right, describe the path your data travels, starting at the enclosing type's table and ending at
> the field's type. The order you write them in is the order the tables are joined; your editor
> shows the resolved path as you type.

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
