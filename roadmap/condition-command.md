---
id: R552
title: "Condition command: the WHERE family as coordinate-keyed condition units"
status: Spec
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
  query. A condition unit returns one `org.jooq.Condition` value, composed into someone else's query,
  and it is the first unit whose parameters are typed domain values rather than the selection set or
  the env: graphql-java is absent from the entity signature entirely. That tests that the command
  vocabulary (UnitRef, producer, total renderer) is not select-specific. It is also the first
  member of R333's operation crosswalk (the `condition` row) realized as a command; `orderBy` (row 9)
  and the rest of the operation seams will follow this template in R549 slice 5, so a template that
  only fits queries would be found out here.
- **A second source kind in the edge view.** R541 already proves a cross-kind `UnitRef` edge
  (launcher to projection); what has no instance yet is a command kind that is itself a *source* of
  edges to more than one target kind: the boundary unit references the entity bodies it folds and the
  external methods it calls. R549 slice 7's claim, that the recompile graph is a projection over the
  command relation, needs edges radiating from more than one kind to be more than a special case.
  Separately, and as scope coordination rather than proof: R541's design fork 1 (how the launcher
  carries its WHERE without a rendered-code escape hatch) dissolves once this relation exists,
  because the `where` slot becomes a `UnitRef` into it.
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
  `FromSelectedField`), alias-prefixing mode, and the outer-argument lift (`SplitRowsMethodEmitter`
  and the inline `$fields` arms pass `liftedOuters = null`; `MultiTablePolymorphicEmitter` reuses
  `computeLiftedOuters` with a populated map, the one lift outside the root shims).

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

Every named condition unit is produced from **two coordinate-keyed command relations** and rendered
by one interpreter per kind, each a total function over its arms. The condition relation, keyed
`(coordinate, resolvedTable)`, carries the ordered predicate list; the boundary relation, populated
exactly for the root SELECT family, carries the env-taking fold unit and its facet fragments and
references the condition rows it folds. The entity classes and the root shims are the two kinds'
renderings (a type-keyed GROUP BY each); the R2 naming locus dissolves, and what remains of the
half-migration is one declared base/view derivation pinned at plan time (see Design). Inline hosts
are untouched: they keep reading `filters()` facts
until their own families migrate (R549 slices 3 and 5), at which point their commands reference the
same relations instead of re-composing the fold.

## Design

- **The command.** Pure records in `command`, no emit-library vocabulary (R549 invariant 3). The
  sketch is illustrative; the invariants stated around it are binding.

  ```java
  record ConditionCommand(
      Coordinate coordinate,        // key: (coordinate, table)
      TableRef table,               // the resolvedTable the predicates land on; polymorphic roots
                                    // expand to one row per participant table
      List<Predicate> predicates)   // 0..N, conjoined by AND; today's conjunct order preserved
  {}

  /** The env-taking fold over a coordinate's condition rows; its own relation, its own renderer. */
  record BoundaryConditionCommand(
      Coordinate coordinate,        // key; populated exactly for the root SELECT family
      UnitRef unit,                 // <Root>Conditions#<field>Condition; R541's `where` points here
      List<FacetFragment> facets)   // masked re-renderings for faceted coordinates; empty otherwise
  {}

  sealed interface Predicate {
      /** Graphitron-minted column predicate; its body is ours to emit, into the unit named here.
          Each ColumnTerm carries its shape (Eq | In | RowEq | RowIn), its presence gate, and its
          own Reach. */
      record Generated(UnitRef body, List<ColumnTerm> terms, List<Binding> bindings)
          implements Predicate {}
      /** Developer @condition method: an opaque external call, no terms of ours at all. */
      record Authored(ExternalRef method, List<Binding> bindings, Reach reach)
          implements Predicate {}
  }

  sealed interface Reach {          // where the predicate's subject rows live
      record Local() implements Reach {}                       // this row's resolvedTable
      record ViaFkPath(List<FkHopRef> hops) implements Reach {} // other rows: correlated EXISTS
  }
  ```

  The boundary row carries no predicate content of its own: what it folds is the condition row(s)
  for its coordinate, referenced by key, so its edge set (to entity bodies and external methods) is
  a total switch over the referenced predicates, per R549's edges-are-a-derived-view rule.

- **Two predicate arms, on one structural axis: who owns the body.** The projection command collapsed
  its provenance distinctions because every arm still rendered to "add these terms"; here one arm
  literally cannot be rendered by us. A `Generated` predicate's body is ours to emit; an `Authored`
  one is an opaque call into consumer code with no terms of ours at all. That single axis manifests
  three ways downstream (the renderer emits a body versus a call, the edge view types the callee
  emitted versus external, and override suppression reaps only generated rows), which is what an arm
  split has to show. Presence-gating is not part of the split: it is per-term data on the generated
  arm (`BodyParam.nonNull()` today), evidence for the grain note below rather than for the arms.
- **Reach is one type, attached at each arm's own grain.** Today the "predicate over rows reached
  through a join path" shape exists twice under different names: `BodyParam.RemoteColumnPredicate`
  (generated, rendered inside the entity method) and `FkTargetConditionFilter` (authored, rendered
  at the fold site). Both render the same SQL shape, a correlated `DSL.exists(selectOne()
  .from(target).where(correlation.and(inner)))`, so under R549's rule (term arms are SQL shapes,
  never reasons) they share one `Reach` type. The grain differs and the command respects it: on the
  generated arm reach is decided per body param today (`FieldBuilder` routes each input binding
  locally or remotely, so one entity method routinely mixes a local `Eq` with a remote EXISTS), and
  `Reach` therefore sits on the `ColumnTerm`; on the authored arm the wrap covers the whole call, so
  it sits on the predicate. One narrowing is a genuine capability: both EXISTS emitters accept only
  FK-derived hops (`JoinStep.Hop` with `On.ColumnPairs`) and throw `IllegalStateException` on
  anything else, so `ViaFkPath` carries FK hop references by type and the two defensive throws
  become unrepresentable. Where the EXISTS is *emitted* (inside the entity body for generated,
  around the call for authored) is renderer placement, not command structure.
- **Bindings are source-relative, and the command owns its own binding vocabulary.** `CallParam` /
  `CallSiteExtraction` already describe an extraction relative to an argument value source, and the
  source is the consuming host's to supply (`ArgumentValueSource.Env` at the boundary unit,
  `FromSelectedField` at inline arms). The command carries the bindings; it does not carry the
  source. That is what lets one relation serve the env-shaped boundary now and the inline hosts
  later without re-minting rows. The home question is decided here rather than discovered at
  implementation: `Binding` is `command`-package vocabulary, not a re-export of the model's
  `CallParam`, which would put a model type inside `command` and break R549 invariant 3 on this
  family's first row. The shared term emitters (`FkTargetConditionEmitter`, `ArgCallEmitter`) are
  re-parameterised over the command arms, and the unmigrated inline hosts reach them through one
  `WhereFilter`-to-`Binding` adapter in `plan`, deleted when slice 5 folds those hosts in. That
  keeps one term emitter and one extraction vocabulary through the coexistence window, instead of a
  third emitter beside the two this design is meant to converge.
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
- **The producer walks coordinates, which is where R472 gets fixed or fenced.** R472's root cause is
  that a `NestingField`'s children have no `schema.types()` entry and an empty `fieldsOf`, so the
  coordinate index itself cannot see them; minting from it changes nothing by itself. The enabler is
  R549 slice 3's promotion of nesting types to projection units, which gives nested coordinates a
  walkable home. If that enumeration is in place when slice 1 lands, the nested
  `GeneratedConditionFilter` gets a row and its body gets emitted, closing the dangling reference; if
  it is not, the validator-mirrors-classifier rule applies: an accepted classification whose emit is
  unimplemented is a `ValidateMojo` deferred rejection, not a silent skip, and R472 converts from a
  wrong-output bug to a build-time rejection until the walk lands. Either outcome retires the bug
  class; only the first retires the item.
- **The covered family, derived and never tagged, with its boundary cases named.** The condition
  relation gets a row per `(coordinate, resolvedTable)` key with a nonempty live filter set;
  polymorphic roots expand to one row per participant (their filters live on `participantFilters()`
  and their own `filters()` is empty, so the expansion is the fact, not a special case). The boundary
  relation's membership is the derived fact R541 states for its covered family (a `RootField` whose
  operation is `Fetch` / `Paginate` / `Lookup` with a table-shaped target) intersected with a
  nonempty live filter set: a covered launcher coordinate with no filters has no boundary row, its
  launcher's `where` slot is absent, and the renderer composes the neutral condition, so absence is
  data rather than an inline escape hatch (today's emitted `return DSL.noCondition();` shims stop
  existing, a shape change with no SQL effect). Two boundary cases are
  named rather than glossed. Lookup coordinates: lookup keys go through the VALUES join and are not
  predicates, and today `TypeConditionsGenerator` silently skips every `LookupField` with an
  in-source "no such schema exists today" note; the producer keeps lookup keys out by the fact, and a
  lookup coordinate carrying a *non-key* filter becomes a `ValidateMojo` deferred rejection instead
  of a silent skip. `@condition(contextArguments:)` on a boundary coordinate: the emit is
  known-broken today (the shim lacks the `graphitronContext(env)` helper it emits a call to,
  documented only in a source comment slice 2 deletes), so the same deferred-rejection treatment
  lands with slice 2, and the broken shape fails the build instead of riding a comment. Stating the
  key up front is what keeps "exactly one row per key" structural instead of quietly false for
  interface and union roots: a participant row differs from its siblings in its `resolvedTable`, so
  the expansion needs no second key column.
- **Facet fragments are masked renderings, not new commands.** Which fragments exist
  (`<field>FacetBaseCondition`, one `<field>Facet_<g>Condition` per facet input) and which parameter
  slots each masks to `null` are producer decisions carried on the boundary row; the boundary
  renderer emits them from the referenced predicate list. Today that knowledge is generator control
  flow in `buildSuppressedConditionMethod`; as data it becomes assertable in the pipeline tier.
- **Conjunct order is preserved, exactly.** Today's fold appends the generated predicate first, then
  authored conditions in argument order (`FieldBuilder.projectFilters` builds the list that way), and
  the command keeps that order verbatim rather than normalising to SDL order. Reordering conjuncts is
  semantics-preserving but changes rendered SQL text, and R541's acceptance pins exact SQL strings;
  an item whose promise is "shape may move, SQL may not" does not spend its budget on a cosmetic
  reorder.
- **One renderer per command kind.** The entity renderer groups condition rows by the generated
  predicate's host class and emits the typed predicate bodies (this is the type-keyed GROUP BY, slice
  3b's exemplar). The boundary renderer groups boundary rows by root type and emits the env-taking
  fold, including facet fragments and the outer-argument lift. Both are total, take no
  `GraphitronSchema` (R549 invariant 1; two more decrements on the 25), and live in `render`. The
  fold shape itself (seed, guarded AND, single-filter short form) is renderer-internal.
  `computeLiftedOuters` is not merely internal today: `MultiTablePolymorphicEmitter` calls it as a
  shared static with a populated map, so its home is a decision this item makes (it moves with the
  boundary renderer; the out-of-scope polymorphic host keeps a delegating call until its family
  migrates), and `QueryConditionsGeneratorLiftTest` moves with the helper.
- **Naming is one locus for the emitted set, with the window stated honestly.** The producer mints
  every `UnitRef` (entity class by return type or participant, boundary class by root type, method
  names, facet fragment names) from the naming vocabulary R549 slice 3 moves out of `compile/`.
  `GeneratedUnits.conditions(parentTypeName)` currently derives only the shim scheme while the
  emitted set spans both schemes; the producer's vocabulary covers both, and the four
  `TypeFetcherGenerator` sites that recompute the shim FQCN plus `buildConditionCall`'s method-name
  formula read the minted `UnitRef` instead. The entity name, though, keeps a second reader until
  slice 5: the model fact (`GeneratedConditionFilter.className()` / `methodName()`, minted in
  `FieldBuilder.projectFilters`) is what the out-of-scope inline hosts render calls from. R541's fork
  2 refused exactly this shape, so the base/view relationship is declared instead of implicit: the
  producer *derives* its entity `UnitRef` from the model fact (the fact is base, the `UnitRef` is
  view), a plan-time assertion pins the two equal for every generated predicate, and the fact is
  listed for retirement when slice 5 removes the last inline reader. The `+ "Condition"` grep from
  R333 thread I then finds one mint site plus one declared derivation, not two independent formulas.

## Design forks for the Spec reviewer

1. **One-layer or two-layer emission.** Today's shape is two layers: typed entity methods (no
   graphql-java import, null guards over typed values) and env-taking boundary shims (extraction plus
   fold). The alternative collapses each covered coordinate to a single env-taking unit with the
   generated predicate bodies inlined. Recommendation: keep two layers. The typed entity method is
   the independently assertable half (seam verdict (c)) and the only condition surface testable
   without graphql-java; the facet fragments depend on calling it with `null` masks; and collapsing
   churns the emitted surface in an item whose acceptance is "shape may move, SQL may not". The cost
   is a second command kind (the boundary relation), which the grain argument above wants anyway: the
   two layers are two kinds, not two slots on one row.
2. **Reach unification.** Fold `RemoteColumnPredicate` and `FkTargetConditionFilter` into one
   `Reach.ViaFkPath` wrap (recommended above), or keep two arms mirroring today's types.
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
   R541's fork 1 was re-posed at its Spec review 2026-07-27 and the paraphrase above is stale in one
   respect: its option (a) is not a "bounded slice of row 5" being pulled in, because the root builders
   already call named `<field>Condition` methods, so (a) is only row 5's naming lift for the covered
   family. Same conclusion, smaller premise; this item's ownership claim is unaffected.

## Slices

Slice 0 is R549 slice 3: `EmitPlan`, `UnitRef`, the `command` / `plan` / `render` packages, and the
naming vocabulary out of `compile/`. Nothing here starts before that lands.

1. **Command, producer, entity renderer, together.** The record set, the producer minting condition
   rows for every covered `(coordinate, resolvedTable)` key (participants expanded, nested
   coordinates per the R472 note), and the entity renderer replacing
   `TypeConditionsGenerator.generate`. Qualified parameter names land here (R475), with a compiling
   fixture; every call site passes arguments positionally, so the rename does not cross into the
   unmigrated inline hosts. Per R549's recipe step 5, the two test surfaces land with it:
   pipeline-tier assertions on produced rows over existing fixtures, and per-arm renderer unit tests
   that retire `TypeConditionsGeneratorTest` (R387). The arm tests assert unit identity, parameter
   names and types, and arm-coverage totality, never `code().toString()` shapes; body correctness
   stays where the tier doctrine puts it, at the compilation and execution tiers. The relation's
   non-vacuity and boundary pins land here for the same reason R541 gives: pins over an empty set
   are not enforcers.
2. **Boundary relation and renderer.** The env-taking fold family replacing
   `QueryConditionsGenerator`: shims, facet fragments as masked renderings, the outer-argument lift,
   FK-target aliasing. The name lift completes here: the four recomputation sites in
   `TypeFetcherGenerator` and `buildConditionCall`'s formula read minted `UnitRef`s, and
   `conditionMethodName` / `facetBaseConditionMethodName` / `facetConditionMethodName` /
   `CLASS_NAME_SUFFIX` retire. This slice owns its own SQL enforcer (see Acceptance) and lands the
   `contextArguments` deferred rejection in the same commit that deletes the comment documenting the
   breakage. The family's migration dial closes here, windowless: the membership enforcer (the
   derived fact's true-set equals the relation's key-set) lands in the same commit that retires the
   generator, matching R541's closing slice.
3. **The launcher handshake.** `LauncherCommand.where` resolves as a `UnitRef` into this relation
   (jointly with R541 slice 1; see fork 4 for the ordering). The cross-kind edge appears in the edge
   view, typed emitted-or-external, and the plan-time closure check covers it. A covered launcher
   coordinate whose live filter set is empty has no row in this relation; the launcher's `where`
   slot is absent there and its renderer composes the neutral condition, so absence is data rather
   than an inline escape hatch.

## Acceptance

- **SQL equivalence, not byte equivalence, with an enforcer this item owns.** The execution tier
  stays green unchanged, and conjunct order is preserved exactly, so whichever of R541 and R552 lands
  first, the other does not edit its pinned strings. A borrowed suite is not this item's enforcer:
  before slice 2's cutover, a boundary-family equivalence pin lands in `graphitron-sakila-example`
  (the same per-test-class `SQL_LOG` `ExecuteListener` idiom R541 specifies), asserting exact
  rendered SQL for one representative faceted, one lifted-outer, and one FK-target coordinate, the
  three riskiest boundary shapes. The existing condition execution tests (`GraphQLQueryTest`,
  `MultiTableFilterExecutionTest`, the fixtures in `graphitron-sakila-service`'s conditions package)
  keep pinning behaviour. Where slice 1 changes emitted Java shape (parameter names), SQL does not
  move.
- **The relations are non-vacuous and correctly bounded.** One condition row per covered
  `(coordinate, resolvedTable)` key: a polymorphic root's row count equals its participant count, and
  a coordinate with an empty live filter set appears zero times. Boundary rows exist exactly for the
  conditions-bearing members of the root SELECT family. The named exclusions hold: no lookup-key predicates, and the two deferred
  rejections (non-key lookup filters, boundary `contextArguments`) fire on their fixtures.
- **The two absorbed defects have fixtures.** R472's nested generated condition compiles and
  executes (or, if the nested walk is deferred, fails the build as a deferred rejection rather than
  emitting a dangling reference). R475's sibling same-named filter fields compile.
- **Closure and edges.** The level-1 oracle (`MethodClosureOracleTest`) stays green over emitted
  output. The plan-time edge view covers boundary-to-body and boundary-to-external edges, and the
  external arm resolves against the same reflection surface `ConditionResolver` uses today. The
  base/view naming pin holds: for every generated predicate, the minted entity `UnitRef` equals the
  model-carried `(className, methodName)` pair, until slice 5 retires the fact.
- **The seam worklist records the verdict.** Per R549 recipe step 6, R333's row 5 and its `condition`
  crosswalk row are updated with the landed verdict when this item ships, so the model item and the
  programme cannot drift on what happened to the seam.
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
  `facetConditionMethodName` / `CLASS_NAME_SUFFIX` (slice 2): the R2 formula locus and the public
  constant its consumers read; names are producer-minted.
- The shim-FQCN recomputation in `TypeFetcherGenerator` (four sites) and the method-name formula in
  `buildConditionCall` (slice 2): call sites read the minted `UnitRef`.
- `computeLiftedOuters` as a shared static on `QueryConditionsGenerator` (slice 2): the helper moves
  with the boundary renderer and `QueryConditionsGeneratorLiftTest` moves with it;
  `MultiTablePolymorphicEmitter` keeps a delegating call until its family migrates.
- `BodyParam.RemoteColumnPredicate` and `FkTargetConditionFilter` as *separate reach expressions*
  (slice 1, conditional on fork 2): the model facts survive until slice-4 re-sourcing, but the
  command expresses both as `Reach.ViaFkPath` and the two EXISTS emitters converge.

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

