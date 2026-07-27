---
id: R552
title: "Condition command: the WHERE family as coordinate-keyed condition units"
status: Backlog
bucket: architecture
priority: 4
theme: classification-model
depends-on: [facts-and-commands]
created: 2026-07-27
last-updated: 2026-07-27
---

# Condition command: the WHERE family as coordinate-keyed condition units

Owns seam-worklist row 5 of R333's living table: the `<field>Condition(...)` unit, emitted today by
`TypeConditionsGenerator` and `QueryConditionsGenerator` and recorded there as the one half-migrated
naming edge (regime 1 at the entity end, regime 2 at the shim end, verdict "finish lift"). This item
is **the third leg of R549's proof sequence**: slice 3 (the projection command) proves type grain and
a contribution list, R541 (slice 3c, the launcher command) proves coordinate keying, strategy as
data, and the first cross-command edge, and this item proves the four things neither can reach
(below). It finishes row 5's lift as a command family rather than as a per-edge naming migration,
which dissolves the R2 locus instead of repointing it.

One model correction up front, so the spec is read against the code rather than the target
vocabulary. R549's hierarchy table lists `condition` among `Operation`'s minted members; that is
R333's target model, not the tree. `Operation` has no condition arm today: the filter surface rides
as a component on two arms (`Operation.Fetch(List<WhereFilter> filters, ...)` and
`Operation.Paginate(...)`), minted in `OutputField.readOperation`. The only arms whose names say
condition are `UpdateMatching` / `DeleteMatching`, both unimplemented and both listed in
`ClassifiedDslTest.OPERATION_KNOWN_GAPS`. This item's relation keys on the coordinate and reads the
filter components where they sit; whether `condition` is later promoted to an `Operation` arm of its
own is R333's model work, and nothing here depends on the answer.

## Why this family is the right third proof

R549 slice 3 proves the projection half; R541 proves the launcher half. Four load-bearing properties
of the command architecture have no instance until this family ships one:

- **A value-shaped unit.** A projection command returns a select-term list; a launcher command owns a
  query. A condition unit returns one `org.jooq.Condition` value, composed into someone else's query.
  It is the first command whose renderer emits a QueryPart-valued helper, so it tests that the
  command vocabulary (UnitRef, producer, total renderer) is not select-specific. It is also the first
  member of R333's operation crosswalk (the `condition` row) realized as a command; `orderBy` (row 9)
  and the rest of the operation seams will follow this template in R549 slice 5, so a template that
  only fits queries would be found out here.
- **A command referenced by another command kind.** R541's `LauncherCommand` carries a `where` slot,
  and its design fork 1 asks how that slot avoids becoming a rendered-code escape hatch. With this
  relation in place the answer is structural: the slot is a `UnitRef` into the condition relation,
  and the cross-kind edge set becomes two kinds deep (launcher to projection, launcher to condition).
  R549 slice 7's claim, that the recompile graph is a projection over the command relation, needs
  edges from more than one command kind to be more than a special case; this is the cheapest second
  edge kind.
- **External-code edges.** An authored `@condition` names a developer method the emit does not own
  (`ConditionFilter` is a `MethodRef` into consumer code). This is the first command carrying a
  callee outside the emitted set, so the edge view learns the emitted-versus-external distinction
  here, at one arm, before the service-call unit (R333 worklist row 11) needs it at scale. The
  closure oracle's "every callee resolves" splits cleanly: emitted callees resolve against the plan,
  external callees resolve against `ServiceCatalog` reflection, and the command records which kind
  each edge is instead of the renderer knowing.
- **The type-keyed fold gets its exemplar.** R549 slice 3b's one worked example is literally this
  family: "emit a conditions class for a type exactly when some coordinate on it carries a condition
  operation". The `<X>Conditions` classes are GROUP BYs over coordinate rows (by return type at the
  entity end, by root type at the shim end), so shipping this relation hands slice 3b its first
  derived `(typeName, unitKind)` rows and validates that the fold direction is right before 3b
  commits to it for all 24 predicates.

A fifth property is not unique to this family but lands here first at full strength: **boundary
marshalling as data.** `CallParam` / `CallSiteExtraction` / `BodyParam` are the argument-extraction
vocabulary that crosses the resolve/SQL line (env arguments and nested input paths on one side, typed
Java parameters on the other), and the condition unit is where all of it concentrates. Neither the
projection command (gated on selection, not arguments) nor the root launcher (its arguments arrive
via the condition and pagination slots) exercises it.

The family is also well-bounded the same way R541's is: membership is derived from facts the model
already carries (a coordinate's live filter set is nonempty), with no exemption list anywhere, so
"did we cover it" is decidable.

## Current topology (2026-07-27 code walk)

Three emit hosts read the same `List<WhereFilter>`; only the first two produce named units.

- **`TypeConditionsGenerator`** (entity classes): one `public class <X>Conditions` per distinct
  `GeneratedConditionFilter.className()`, grouped by the FQCN the *model* carries (minted once in
  `FieldBuilder.projectFilters` as `<outputPackage>.conditions.<ReturnType>Conditions`, method
  `<fieldName>Condition`; regime 1 on both ends). Each method takes the jOOQ table plus typed body
  params and folds `DSL.noCondition()` with guarded `.and(term)` steps; guards read
  `BodyParam.nonNull()` and list-ness; terms switch over the body-param arms `Eq` / `In` / `RowEq` /
  `RowIn`, with `RemoteColumnPredicate` rendering a correlated `DSL.exists(...)` over a
  `JoinStep.Hop` path. Participant filters on interface/union roots produce per-participant
  `<Participant>Conditions` classes through the same path. Structural blindness, filed as R472: the
  walk is `schema.types()` × `fieldsOf(type)`, which cannot see fields nested inside a
  `ChildField.NestingField`, so a classifier-attached nested `GeneratedConditionFilter` produces a
  call to a method that is never emitted.
- **`QueryConditionsGenerator`** (root shims): one `<RootType>Conditions` per root type with SQL
  fields, one `public static Condition <field>Condition(<JooqTable> table, DataFetchingEnvironment
  env)` per covered coordinate. The method extracts argument values (nested-ternary `instanceof Map`
  chains, R334's complaint), lifts an outer argument shared by two or more nested extractions into a
  `Map` local (`computeLiftedOuters`), declares FK-target aliases, and folds terms through the shared
  `FkTargetConditionEmitter.emitTerm` (plain arm: `ClassName.methodName(baseAlias, args...)`;
  FK-target arm: correlated `DSL.exists` around the developer method). Faceted coordinates get
  fragment methods (`<field>FacetBaseCondition`, `<field>Facet_<g>Condition`) that re-render the same
  filter list with per-parameter `null` masks. **Names are recomputed here** (`fieldName +
  "Condition"` in `conditionMethodName` and the two facet variants), and recomputed *again* at the
  consumer: `TypeFetcherGenerator` derives the shim FQCN at four separate sites and
  `buildConditionCall` re-derives the method name. This is the R2 end row 5 wants lifted.
- **Everything else composes the identical fold inline.** `SplitRowsMethodEmitter.buildWhereCondition`
  (child rows methods), `InlineTableFieldEmitter` and its column/lookup siblings (inline `$fields`
  arms), `MultiTablePolymorphicEmitter` (per-branch WHERE), `LookupValuesJoinEmitter` and
  `SelectMethodBody`: each seeds `DSL.noCondition()`, ANDs hop filters and field filters through the
  same `FkTargetConditionEmitter.emitTerm`, and differs only in `ArgumentValueSource` (`Env` versus
  `FromSelectedField`), alias-prefixing mode, and the outer-argument lift (root-only; every inline
  site passes `liftedOuters = null`).

Model facts in place: the sealed `WhereFilter` (`GeneratedConditionFilter` with its `bodyParams`,
`ConditionFilter` as the authored `MethodRef` pinned to return `org.jooq.Condition`,
`FkTargetConditionFilter` wrapping an authored method whose implicit first parameter is the FK-target
table, with join path and correlation columns). Override suppression is already resolved upstream:
`FieldBuilder.projectFilters` expresses `@condition(override: true)` purely as the *absence* of the
suppressed `GeneratedConditionFilter`, so a consumer of `filters()` never sees a suppressed row.
`MethodCommandRegistry` does not cover condition methods; the level-2 closure pattern never reached
this family.

Known defects in the family, each filed separately: R472 (nested generated filters never emitted),
R475 (same-named filter fields across sibling arguments collide as Java parameters, output fails the
consumer's javac), R334 (argument extraction is an unreadable ternary chain), R387
(`TypeConditionsGeneratorTest` asserts on generated body strings against the testing doctrine). A
documented in-source bug: `@condition(contextArguments:)` reaching `QueryConditions` emits a
`graphitronContext(env)` helper call the shim class does not have.

## Target

Every named condition unit is produced as a **condition command** in the coordinate-keyed relation
and rendered by interpreters that are total functions over it. One command per conditions-bearing
coordinate; the command carries the resolved table, the ordered predicate list, and the
producer-computed unit names. The entity classes and the root shims become two renderings of the one
relation (a type-keyed GROUP BY each); the name exists at exactly one locus, the producer, so row 5's
half-migration ends by construction. Inline hosts are untouched: they keep reading `filters()` facts
until their own families migrate (R549 slices 3 and 5), at which point their commands reference the
same relation instead of re-composing the fold.

## Design

- **The command.** Pure records in `command`, no emit-library vocabulary (R549 invariant 3). The
  sketch is illustrative; the invariants stated around it are binding.

  ```java
  record ConditionCommand(
      Coordinate coordinate,        // the relation's key
      TableRef table,               // the resolvedTable the predicates land on
      List<Predicate> predicates,   // 0..N, SDL order, conjoined by AND at every consumer
      Optional<UnitRef> boundary,   // the env-taking fold unit; present for the root SELECT family
      List<FacetFragment> facets)   // masked re-renderings for faceted coordinates; empty otherwise
  {}

  sealed interface Predicate {
      /** Graphitron-minted column predicate; its body is emitted into the unit this row names. */
      record Generated(UnitRef body, List<ColumnTerm> terms, List<Binding> bindings, Reach reach)
          implements Predicate {}
      /** Developer @condition method; opaque, known only by external reference. */
      record Authored(ExternalRef method, List<Binding> bindings, Reach reach)
          implements Predicate {}
  }

  sealed interface Reach {          // where the predicate's subject rows live
      record Local() implements Reach {}                    // this coordinate's resolvedTable
      record ViaPath(List<JoinRef> hops) implements Reach {} // other rows: correlated EXISTS
  }
  ```

- **Two predicate arms, because provenance here is load-bearing.** The projection command collapsed
  its provenance distinctions because nothing downstream needed them; conditions are the opposite
  case. R333 gives the two provenances different semantics (generated rows carry presence-gating,
  authored rows never do; override suppression reaps only generated rows), the renderer emits them
  differently (a generated predicate's body is ours to emit, an authored one is a call into consumer
  code), and the edge view types them differently (emitted versus external callee). That is three
  downstream consumers of the distinction, which is what an arm split has to show.
- **Reach is a wrap, not more arms.** Today the "predicate over rows reached through a join path"
  shape exists twice under different names: `BodyParam.RemoteColumnPredicate` (generated arm,
  rendered inside the entity method) and `FkTargetConditionFilter` (authored arm, rendered at the
  fold site). Both render the same SQL shape, a correlated `DSL.exists(selectOne().from(target)
  .where(correlation.and(inner)))`, so under R549's rule (term arms are SQL shapes, never reasons)
  they are one `Reach.ViaPath` slot appearing on both arms, and the two EXISTS emitters converge on
  one. Where the EXISTS is *emitted* (inside the entity body for generated, around the call for
  authored) is renderer placement, not command structure.
- **Bindings are source-relative.** `CallParam` / `CallSiteExtraction` already describe an extraction
  relative to an argument value source, and the source is the consuming host's to supply
  (`ArgumentValueSource.Env` at the boundary unit, `FromSelectedField` at inline arms). The command
  carries the bindings; it does not carry the source. That is what lets one relation serve the
  env-shaped boundary now and the inline hosts later without re-minting rows.
- **Suppression stays where it is resolved.** Union-then-suppress (R333's `generated_op ... is live
  iff` rule) is already computed in `FieldBuilder.projectFilters`, and a suppressed generated
  predicate is already absent from `filters()`. The producer reads the resolved lists, per R549's
  "slice 3 needs no fact walk" discipline: production reads exactly where the generators read today,
  and re-sourcing onto the three raw relations (`generated_condition` / `authored_condition` /
  `consumes`) is slice 4 territory. No suppression logic moves in this item.
- **Parameter names are producer-computed, and that is the R475 fix.** The entity method's Java
  parameter list is a decision, currently made implicitly by keying on the bare input-field name,
  which collides across sibling arguments and ships uncompilable output. The producer computes
  qualified, collision-free parameter names as data on the command (the binding rows carry them), so
  the fixture R475 describes compiles. This is the item's capability payload, per R549's "no slice
  that is purely a migration payment".
- **The producer walks coordinates, and that is the R472 fix.** Minting from the coordinate relation
  rather than the `schema.types()` × `fieldsOf` product means a nested `GeneratedConditionFilter`
  gets a row and its body gets emitted, closing the dangling reference R472 documents. The
  implementer must verify how nested coordinates are enumerated (through `ChildField.NestingField`
  children); if the walk cannot reach them yet, the same validator-mirrors-classifier rule applies
  that R541 uses: an accepted classification whose emit is unimplemented is a `ValidateMojo` deferred
  rejection, not a silent skip, and R472's case converts from a wrong-output bug to a build-time
  rejection until the walk lands.
- **The covered family, derived and never tagged.** The producer mints a row for every coordinate
  whose live filter set is nonempty, plus the participant-filter rows interface/union roots expand
  to. The `boundary` slot is present exactly when the coordinate is in the root SELECT family (the
  same derived fact R541 states: a `RootField` whose operation is `Fetch` / `Paginate` / `Lookup`
  with a table-shaped target). No exemption list anywhere.
- **Facet fragments are masked renderings, not new commands.** Which fragments exist
  (`<field>FacetBaseCondition`, one `<field>Facet_<g>Condition` per facet input) and which parameter
  slots each masks to `null` are producer decisions carried on the command; the boundary renderer
  emits them from the same predicate list. Today that knowledge is generator control flow in
  `buildSuppressedConditionMethod`; as data it becomes assertable in the pipeline tier.
- **Two renderers over one relation.** The entity renderer groups rows by the generated predicate's
  host class and emits the typed predicate bodies (this is the type-keyed GROUP BY, slice 3b's
  exemplar). The boundary renderer groups boundary units by root type and emits the env-taking fold,
  including facet fragments and the outer-argument lift. Both are total, take no `GraphitronSchema`
  (R549 invariant 1; two more decrements on the 25), and live in `render`. The fold shape itself
  (seed, guarded AND, single-filter short form) is renderer-internal; so is `computeLiftedOuters`,
  which decides Java locals, not what is emitted for whom.
- **Naming is one locus.** The producer mints every `UnitRef` (entity class by return type or
  participant, boundary class by root type, method names, facet fragment names) from the naming
  vocabulary R549 slice 3 moves out of `compile/`. `GeneratedUnits.conditions(parentTypeName)`
  currently derives only the shim scheme while the emitted set spans both schemes; the producer's
  vocabulary covers both, and the four `TypeFetcherGenerator` sites that recompute the shim FQCN plus
  `buildConditionCall`'s method-name formula read the minted `UnitRef` instead. The `+ "Condition"`
  grep from R333 thread I then finds exactly one site.

## Design forks for the Spec reviewer

1. **One-layer or two-layer emission.** Today's shape is two layers: typed entity methods (no
   graphql-java import, null guards over typed values) and env-taking boundary shims (extraction plus
   fold). The alternative collapses each covered coordinate to a single env-taking unit with the
   generated predicate bodies inlined. Recommendation: keep two layers. The typed entity method is
   the independently assertable half (seam verdict (c)) and the only condition surface testable
   without graphql-java; the facet fragments depend on calling it with `null` masks; and collapsing
   churns the emitted surface in an item whose acceptance is "shape may move, SQL may not". The cost
   is carrying two unit names per coordinate on the command, which the producer computes anyway.
2. **Reach unification.** Fold `RemoteColumnPredicate` and `FkTargetConditionFilter` into one
   `Reach.ViaPath` wrap (recommended above), or keep two arms mirroring today's types.
   Recommendation: unify. The risk to weigh is that the two sites render the EXISTS at different
   nesting depths (inside the entity body versus around the authored call), so the renderer carries
   a placement switch on the arm; if that switch turns out to need more than the arm itself to
   decide, the unification was wrong and the reviewer should expect the implementer to say so in the
   In Review hand-off.
3. **Is the R475 fix in scope?** Qualifying parameter names changes the entity methods' signatures,
   which is emitted-shape change beyond a pure re-homing. Recommendation: in scope, as slice 1's
   capability payload; the alternative (rejecting sibling-name collisions at classify time) leaves a
   working schema shape rejected for an emitter limitation the reframing removes for free. R475's
   own body already prefers the qualified-name fix and names the threading through `BodyParam` /
   `CallParam` this design makes natural.
4. **Who owns the launcher handshake.** R541's fork 1 currently offers (a) the launcher completes
   the conditions migration for its covered family, or (b) an opaque escape-hatch arm.
   Recommendation: this item dissolves that fork. R552 owns condition production wholesale; R541's
   `where` slot becomes a `UnitRef` into this relation, and its "bounded slice of row 5" reduces to
   consuming what this item produces. Sequencing follows: R552 slices 1 and 2 land before (or with)
   R541 slice 1. If the reviewer prefers R541 to land first, option (a) stands there and this item's
   slice 2 shrinks to re-homing what R541 built; both orders are safe, one builds the seam twice.

## Slices

Slice 0 is R549 slice 3: `EmitPlan`, `UnitRef`, the `command` / `plan` / `render` packages, and the
naming vocabulary out of `compile/`. Nothing here starts before that lands.

1. **Command, producer, entity renderer, together.** The record set, the producer minting rows for
   every conditions-bearing coordinate (participants included, nested coordinates per the R472 walk),
   and the entity renderer replacing `TypeConditionsGenerator.generate`. Qualified parameter names
   land here (R475), with a compiling fixture. Per R549's recipe step 5, the two test surfaces land
   with it: pipeline-tier assertions on produced rows over existing fixtures, and per-arm unit tests
   on the renderer that retire `TypeConditionsGeneratorTest`'s code-string assertions (R387). The
   relation's non-vacuity and boundary pins land here for the same reason R541 gives: pins over an
   empty set are not enforcers.
2. **Boundary renderer.** The env-taking fold family replacing `QueryConditionsGenerator`: shims,
   facet fragments as masked renderings, the outer-argument lift, FK-target aliasing. The name lift
   completes here: the four recomputation sites in `TypeFetcherGenerator` and `buildConditionCall`'s
   formula read minted `UnitRef`s, and `conditionMethodName` / `facetBaseConditionMethodName` /
   `facetConditionMethodName` retire.
3. **The launcher handshake.** `LauncherCommand.where` resolves as a `UnitRef` into this relation
   (jointly with R541 slice 1; see fork 4 for the ordering). The cross-kind edge appears in the edge
   view, typed emitted-or-external, and the plan-time closure check covers it.

## Acceptance

- **SQL equivalence, not byte equivalence.** The execution tier stays green unchanged; R541's
  equivalence pin suite covers the WHERE clauses of the covered root shapes once both items are in
  flight, and the existing condition execution tests (`GraphQLQueryTest`,
  `MultiTableFilterExecutionTest`, the fixtures in `graphitron-sakila-service`'s conditions package)
  pin the rest. Where slice 1 changes emitted Java shape (parameter names), SQL does not move.
- **The relation is non-vacuous and correctly bounded.** Every conditions-bearing coordinate in the
  corpus appears exactly once; coordinates whose live filter set is empty appear zero times; the
  `boundary` slot is present exactly on the root SELECT family and absent elsewhere.
- **The two absorbed defects have fixtures.** R472's nested generated condition compiles and
  executes (or, if the nested walk is deferred, fails the build as a deferred rejection rather than
  emitting a dangling reference). R475's sibling same-named filter fields compile.
- **Closure and edges.** The level-1 oracle (`MethodClosureOracleTest`) stays green over emitted
  output. The plan-time edge view covers boundary-to-body and boundary-to-external edges, and the
  external arm resolves against the same reflection surface `ConditionResolver` uses today.
- **Third-proof findings are written down.** The In Review hand-off states whether the value-shaped
  unit fit the command template, whether the `Reach` unification held (fork 2's placement switch),
  whether the external-callee edge shape worked, and whether the type-keyed GROUP BY validated slice
  3b's direction. R549 slice 5 generalises from the three proofs together; a hand-off that reports
  nothing has not been read carefully.

## Retired vocabulary

- `TypeConditionsGenerator.generate(GraphitronSchema, String)` and
  `QueryConditionsGenerator.generate(GraphitronSchema, String)` as schema-taking entry points (slice
  1 and slice 2 respectively): both become renderers over the relation; two decrements on R549
  invariant 1's ratchet.
- `QueryConditionsGenerator.conditionMethodName` / `facetBaseConditionMethodName` /
  `facetConditionMethodName` (slice 2): the R2 formula locus; names are producer-minted.
- The shim-FQCN recomputation in `TypeFetcherGenerator` (four sites) and the method-name formula in
  `buildConditionCall` (slice 2): call sites read the minted `UnitRef`.
- `BodyParam.RemoteColumnPredicate` and `FkTargetConditionFilter` as *separate reach expressions*
  (slice 1, conditional on fork 2): the model facts survive until slice-4 re-sourcing, but the
  command expresses both as `Reach.ViaPath` and the two EXISTS emitters converge.

## Out of scope

- **The inline folds.** `SplitRowsMethodEmitter`, the inline `$fields` arm emitters,
  `MultiTablePolymorphicEmitter`'s per-branch WHERE, `LookupValuesJoinEmitter`, `SelectMethodBody`:
  each keeps reading `filters()` facts and composing inline until its own family migrates (R549
  slices 3 and 5). This item changes what they will eventually reference, not what they do now.
- **Mutation conditions** (R245): `@condition` on mutations is a no-op at emit today; wiring it is
  that item's work, and it lands as new rows in this relation when it does.
- **`DSLContext` injection on `@condition` methods** (R11): a resolver/reflection change, orthogonal
  to who produces the unit.
- **The `Single`-wrapper value-gating semantic** (R333's one open condition semantic: predicate into
  a `LEFT JOIN` ON clause so a failing predicate nulls the value rather than dropping the row). That
  is a behaviour change to the model's target semantics, not a re-homing, and nothing here blocks it.
- **Re-sourcing suppression onto the raw relations** (`generated_condition` / `authored_condition` /
  `consumes`): R549 slice 4.
- **`UpdateMatching` / `DeleteMatching`**: unimplemented DML arms, tracked by their known-gap
  entries; not conditions in this family's sense.
- **R334's extraction readability**: the boundary renderer inherits today's ternary chains verbatim.
  The reframing makes the fix cheaper (one renderer instead of scattered emit sites), and R334 stays
  its own item.

## Relationship to existing items

| item | relationship |
|---|---|
| R549 (Spec) | governs; this item is the third proof alongside slice 3 and slice 3c. Slice 5's generalisation gate widens from two proofs to three, and slice 3b gains its exemplar rows from the entity renderer's GROUP BY |
| R541 (Spec) | consumer. Its fork 1 dissolves into fork 4 here: the launcher's `where` slot is a `UnitRef` into this relation, and its bounded row-5 scope reduces to consumption. Cross-referenced there |
| R333 (Ready) | owns row 5 and the target condition semantics; this item executes the row's "finish lift" verdict in command form and does not touch the `Single` value-gating semantic or the raw-relation re-sourcing |
| R475 (Backlog) | absorbed: slice 1's qualified parameter names are its preferred fix. Tombstone at pickup, delete when this item reaches Done |
| R472 (Backlog) | absorbed: the coordinate walk (or its deferred rejection) is the fix. Same tombstone treatment |
| R387 (Backlog) | absorbed by slice 1's per-arm renderer tests, which is R549 recipe step 5 applied to this family |
| R334 (Backlog) | enabled, not folded: extraction rendering concentrates into one renderer, and the item stays open |
| R11 (Backlog) | untouched: resolver-side, feeds new binding rows into the same relation when it lands |
| R245 (Backlog) | untouched: mutation conditions join the relation when their emit exists |

