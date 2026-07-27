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
  edges to more than one target kind: the glue unit references the entity bodies it folds and the
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

One coordinate-keyed command relation, two total renderings, one consumption rule. The relation,
keyed `(coordinate, resolvedTable)`, carries the ordered predicate list; every row also names its
**glue unit**, the emitted method that extracts this coordinate's argument values and folds its
predicates into one `Condition`. The entity classes (typed predicate bodies) and the glue methods
are the two renderings, each a type-keyed GROUP BY over the same rows. The consumption rule is the
owner's stated requirement, readability and debuggability of the generated output: **every WHERE
consumer calls glue; nobody composes the fold inline.** The root fetcher, the child rows method,
the inline `$fields` arm, and the polymorphic branch all emit the same one-line call,
`<Parent>Conditions.<field>Condition(<alias>, <argsMap>)`, and the extraction ternaries exist in
exactly one generated place per coordinate, as named locals inside the glue body. Today's root-only
shim was this rule applied to one consumer; this item applies it to all of them. The R2 naming locus
dissolves, and what remains of the half-migration is one declared base/view derivation pinned at
plan time (see Design).

This is a deliberate strengthening over the previous draft, recorded so the reviewer sees the
reasoning and not just the result. The draft modelled a root-only "boundary" relation; the code walk
below shows the extraction-plus-fold is performed identically at every consuming site (a child rows
method does `.and(FilmConditions.filmsByMixedFilterSplitCondition(f0, decodeFilmKeysOrThrow(
env.getArgument("filter") instanceof Map<?, ?> map1 ? map1.get("ids") : null)))` inline, and the
polymorphic branches even reuse the outer-argument lift), so "boundary" was one consumer's emission
convenience promoted to a command kind. The corrected model makes the glue total, one per condition
row, and the readability requirement is what pays for the extra emitted methods.

## Design

- **The command.** Pure records in `command`, no emit-library vocabulary (R549 invariant 3). The
  sketch is illustrative; the invariants stated around it are binding.

  ```java
  record ConditionCommand(
      Coordinate coordinate,        // key: (coordinate, table)
      TableRef table,               // the resolvedTable the predicates land on; polymorphic roots
                                    // expand to one row per participant table
      List<Predicate> predicates,   // 1..N, conjoined by AND; today's conjunct order preserved
      UnitRef glue,                 // <Parent>Conditions#<field>Condition, the fold every consumer
                                    // calls; total, one per row; R541's `where` points here
      List<FacetFragment> facets)   // masked glue variants; present exactly when the coordinate
                                    // carries facet inputs, gated by that fact
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

  The glue slot is total, so it is a slot rather than a second relation: a 1:1 derived unit rides
  its row, unlike the previous draft's 0:1 boundary population, which needed a relation of its own
  to satisfy the grain rule. The glue carries no predicate content; its edge set (to the entity
  bodies it folds and the external methods it calls) is a total switch over the row's predicates,
  per R549's edges-are-a-derived-view rule.

- **The glue signature unifies the source fork.** `ArgumentValueSource`'s own javadoc states that
  the generated condition and decode logic are identical at every site and only the
  `getArgument`-shaped read expression forks: `env.getArgument(name)` where the enclosing env is the
  field's own, `<sf>.getArguments().get(name)` at the inline sites where env belongs to an ancestor.
  Both surfaces expose the same coerced `Map<String, Object>`, so the glue takes the map:
  `public static Condition <field>Condition(<JooqTable> table, Map<String, Object> args)`. The
  caller supplies `env.getArguments()` or `<sf>.getArguments()`, the fork moves out of the emit
  machinery and into one call-site expression, and `ArgumentValueSource` retires with the last
  inline fold. Two riders. A coordinate whose authored condition consumes `contextArguments` gets
  `DataFetchingEnvironment env` appended to its glue signature, producer-decided per row; that is
  also what turns the known-broken `contextArguments` emit (the shim calling a `graphitronContext`
  helper it does not have) from a deferred rejection into an implementable slot, since context is
  request-global and the ancestor env at inline sites serves it correctly. And the implementer
  verifies the equivalence `env.getArgument(name)` versus `env.getArguments().get(name)` against
  graphql-java before relying on it; the current tree already treats the two as interchangeable
  across sites, so this is a confirmation, not a gamble.
- **The glue body is the readability contract.** One named local per argument (extraction, decode,
  enum coercion, with the nested-path `instanceof` chains landing on the local's right-hand side),
  then one call per predicate, folded. `films` renders as locals for `rating`, `textRating`,
  `maxRentalRate` followed by `return FilmConditions.filmsCondition(table, rating, textRating,
  maxRentalRate);`. This is R334's fix for the condition family, absorbed here: the ternary chains
  stop appearing at call sites entirely and stop appearing as inline call arguments even within the
  glue. The outer-argument lift (`computeLiftedOuters`) generalises into this convention, since "a
  shared outer becomes a local" is just the locals rule applied to a prefix of the path, and the
  `JooqConvert` list pre-lift follows the same way.
- **Glue hosting generalises today's shim rule instead of inventing one.** Glue for coordinate
  `Parent.field` lives on `<Parent>Conditions` as `<field>Condition`; the root family's
  `QueryConditions` is the existing instance of that rule (parent type `Query`), so root output
  keeps its shape while child and inline coordinates gain methods on classes that follow the same
  formula. `GeneratedUnits.conditions(parentTypeName)`, whose javadoc already says "per-parent"
  while only the shim end honoured it, becomes true as stated. Participant rows on one coordinate
  disambiguate by producer-minted name (the producer computes every name, so this is a naming-
  vocabulary decision, not a formula at the emit site). Where parent and return type coincide, the
  entity method (typed parameters) and the glue method (args map) are overloads on one class, which
  is legal and readable.
- **This family mints the shared term algebra, because it gets there first.** Under R549's ordering
  this is the first command family to land, and R549's shared-vocabulary rule makes the column
  reference programme-owned core rather than family vocabulary: `table.COL` is the same SQL shape
  whether it is projected or compared, and term arms are SQL shapes, never reasons. So the column
  reference `ColumnTerm` names goes in `command` as core, and slice 3.1's `SelectTerm` extends it
  rather than standing up a parallel type beside it. The comparison wrapping (`Eq` / `In` / `RowEq` /
  `RowIn`) is *not* core: those are comparison shapes, condition-family vocabulary holding a core
  column reference. Getting this split right is cheap here and expensive after two families have
  shipped their own, which is why R549 names it as one of the two decisions the vocabulary rule
  forces. The other, whether `Coordinate` in `command` is graphql-java's `FieldCoordinates` or a
  command-package type adapted at the plan boundary, is settled in R549 slice 1 and consumed here.
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
- **Bindings are map-relative, and the command owns its own binding vocabulary.** `CallParam` /
  `CallSiteExtraction` already describe an extraction; under the glue signature the extraction is
  always rooted at the args map, so bindings need no source axis at all. The home question is
  decided here rather than discovered at implementation: `Binding` is `command`-package vocabulary,
  not a re-export of the model's `CallParam`, which would put a model type inside `command` and
  break R549 invariant 3 on this family's first row. The extraction and term machinery
  (`FkTargetConditionEmitter`, `ArgCallEmitter`) is re-parameterised over the command arms and
  becomes internal to the glue renderer; the call-site convergence slice removes the last inline
  users, so no `WhereFilter` adapter and no coexistence emitter is needed, and unmigrated hosts
  interact with this family only by emitting a one-line call to a name.
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
  R549 slice 3.1's promotion of nesting types to projection units, which gives nested coordinates a
  walkable home. **Under R549's decided ordering that enumeration does not exist yet when slice 1
  lands**, so the second branch is the live one rather than a contingency: the validator-mirrors-
  classifier rule applies, an accepted classification whose emit is unimplemented becomes a
  `ValidateMojo` deferred rejection rather than a silent skip, and R472 converts from a wrong-output
  bug (a call emitted to a method that is never generated) into a build-time rejection. That retires
  the bug class immediately and leaves the item open. The fix proper lands when slice 3.1 does: the
  producer's walk picks up the nested coordinates, the rejection deletes for free, and R472 closes.
  Plan for that two-step rather than for the one-step the earlier draft's conditional implied, and
  keep a fixture for both states so the transition is a test flipping from rejected to emitted.
- **The covered family, derived and never tagged, with its edge cases named.** The relation gets a
  row per `(coordinate, resolvedTable)` key with a nonempty live filter set; polymorphic roots
  expand to one row per participant (their filters live on `participantFilters()` and their own
  `filters()` is empty, so the expansion is the fact, not a special case). Glue is total on the
  relation, so its population needs no rule of its own: a coordinate with no live filters has no
  row, no glue, and no call site, and every consumer composes the neutral condition from that
  absence (today's emitted `return DSL.noCondition();` shims stop existing, a shape change with no
  SQL effect). Two edge cases are named rather than glossed. Lookup coordinates: lookup keys go through the VALUES join and are not
  predicates, and today `TypeConditionsGenerator` silently skips every `LookupField` with an
  in-source "no such schema exists today" note; the producer keeps lookup keys out by the fact, and a
  lookup coordinate carrying a *non-key* filter becomes a `ValidateMojo` deferred rejection instead
  of a silent skip. `@condition(contextArguments:)`: the emit is known-broken today (the shim emits
  a call to a `graphitronContext(env)` helper the class does not have, documented only in a source
  comment slice 2 deletes); the env-appending glue signature makes it implementable, so slice 2
  either implements it with a fixture or lands the `ValidateMojo` deferred rejection, and the one
  option that is already wrong is inheriting the silent breakage. Stating the key up front is what
  keeps "exactly one row per key" structural instead of quietly false for interface and union
  roots: a participant row differs from its siblings in its `resolvedTable`, so the expansion needs
  no second key column.
- **Facet fragments are masked glue variants, not new commands.** Which fragments exist
  (`<field>FacetBaseCondition`, one `<field>Facet_<g>Condition` per facet input) and which parameter
  slots each masks to `null` are producer decisions carried on the row, present exactly when the
  coordinate carries facet inputs (gated by a fact, so the slot-implies-slot coupling the previous
  draft had is gone); the glue renderer emits them from the same predicate list. Today that
  knowledge is generator control flow in `buildSuppressedConditionMethod`; as data it becomes
  assertable in the pipeline tier.
- **Conjunct order is preserved, exactly.** Today's fold appends the generated predicate first, then
  authored conditions in argument order (`FieldBuilder.projectFilters` builds the list that way), and
  the command keeps that order verbatim rather than normalising to SDL order. Reordering conjuncts is
  semantics-preserving but changes rendered SQL text, and R541's acceptance pins exact SQL strings;
  an item whose promise is "shape may move, SQL may not" does not spend its budget on a cosmetic
  reorder.
- **Two total renderings of one kind.** The entity renderer groups rows by the generated predicate's
  host class and emits the typed predicate bodies (this is the type-keyed GROUP BY, slice 3b's
  exemplar). The glue renderer groups rows by parent type and emits the fold methods, including
  facet fragments and the extraction locals. Both are total over the same relation with nothing to
  branch on (the previous draft's `Optional` is gone, which is what R549's one-renderer-per-kind
  rule was protecting), take no `GraphitronSchema` (R549 invariant 1; two more decrements on the
  25), and live in `render`. The fold shape itself (seed, guarded AND, single-filter short form) is
  renderer-internal. `computeLiftedOuters` dissolves into the locals convention rather than moving:
  its two callers (`QueryConditionsGenerator` and `MultiTablePolymorphicEmitter`'s branch folds) are
  both replaced by glue calls, and `QueryConditionsGeneratorLiftTest` retires with it.
- **Naming is one locus for the emitted set, with the window stated honestly.** The producer mints
  every `UnitRef` (entity class by return type or participant, glue class by parent type, method
  names, facet fragment names) from the naming vocabulary R549 slice 1 moves out of `compile/`.
  `GeneratedUnits.conditions(parentTypeName)` currently derives only the shim scheme while the
  emitted set spans both schemes; the producer's vocabulary covers both, and the four
  `TypeFetcherGenerator` sites that recompute the shim FQCN plus `buildConditionCall`'s method-name
  formula read the minted `UnitRef` instead. One second reader remains until slice 5: the
  convergence slice's call sites live in unmigrated, schema-fed generators, which read the glue name
  through the model fact (`GeneratedConditionFilter.className()` / `methodName()`, minted in
  `FieldBuilder.projectFilters`) rather than through the plan. R541's fork 2 refused an undeclared
  version of this shape, so the base/view relationship is declared instead of implicit: the producer
  *derives* its `UnitRef`s from the model fact (the fact is base, the `UnitRef` is view), a
  plan-time assertion pins the two equal for every row, and the fact is listed for retirement when
  slice 5 removes the last schema-fed reader. The `+ "Condition"` grep from R333 thread I then finds
  one mint site plus one declared derivation, not two independent formulas.

## Design forks for the Spec reviewer

1. **One-layer or two-layer emission.** Today's shape is two layers: typed entity methods (no
   graphql-java import, null guards over typed values) and glue (extraction plus fold). The
   alternative collapses each covered coordinate to a single map-taking unit with the generated
   predicate bodies inlined. Recommendation: keep two layers. The typed entity method is the
   independently assertable half (seam verdict (c)) and the only condition surface testable without
   graphql-java; the facet fragments depend on calling it with `null` masks; the glue body's
   locals-then-one-call shape is the readability contract, and inlining predicate bodies into it
   would trade that away. The cost is one more emitted method per conditions-bearing coordinate,
   which the readability requirement pays for.
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
4. **Who owns the launcher handshake. Resolved 2026-07-27, at the programme.** This fork and R541's
   fork 1 were each recommending the same answer while deferring the sequencing to the other item's
   reviewer, so neither closed. R549's Slices section decides it: **this item lands first**, slices 1
   to 3 before R541 slice 1. R552 owns condition production wholesale, R541's `where` slot is the
   row's glue `UnitRef`, and R541 consumes rather than builds. The reasons, recorded so the decision
   is auditable rather than arbitrary: four of R541's five root shapes already call named
   `<field>Condition` methods, so the launcher wants the relation from its first row; the reverse
   order has R541 mint a ref from a formula this item then re-homes, which is a migration payment;
   this item needs only R549 slice 1 to start, where R541 needs slices 3.1 and 3.2; and this item
   carries two fixes for output that does not compile today (R472, R475), which is a better first
   family for the programme to be judged on than a purely architectural one.
   The premise correction that shrank R541's side of this stands and is worth keeping: the root
   builders already call named methods, so R541's old option (a) was only row 5's naming lift for its
   covered family, never new condition machinery.
5. **The FK-target alias scheme inside glue.** Today the EXISTS aliases fork by host: static
   `table_fkt<f>_<h>` locals at the shim, runtime-prefixed (`<base>.getName() + "_fkt..."`) at the
   recursion-prone inline sites. Glue methods are per-coordinate scopes, but two glue methods land
   in one query on polymorphic roots (one per participant branch of the UNION), so static names can
   collide across branches. Options: runtime-prefixed everywhere (uniform, matches the inline
   convention, changes the root family's rendered alias text), or a static per-row prefix derived
   from the participant (keeps aliases static and unique, one more naming rule). Recommendation:
   runtime-prefixed everywhere; one convention beats two, and the inline sites already prove it.
   Either way the decision lands in slice 2 *before* the SQL pins are authored, so the pins never
   move afterwards; alias text is the one place this item's SQL is allowed to differ from today's,
   and the pin suite is written against the post-decision strings.

## Slices

Slice 0 is **R549 slice 1**, not slice 3: `EmitPlan`, `UnitRef`, the `command` / `plan` / `render`
packages, and the naming vocabulary out of `compile/` all moved onto the programme's cheapest slice
when it was rescoped, and none of the rest of the keystone is a prerequisite here. That makes this
item the first command family to land, which is the ordering R549's Slices section now fixes, and it
is why the sequencing question in fork 4 is closed rather than open.

R549 slice 3.1 is a **soft** dependency of exactly one thing: the nested-coordinate walk that fixes
R472. Promoting nesting types to projection units is what gives a nested `GeneratedConditionFilter` a
walkable home; without it, the producer cannot see those coordinates and R472 converts to a deferred
rejection instead of a fix, per the R472 note in Design. Everything else here runs against slice 1
alone.

One hard ordering constraint runs the other way and belongs here rather than in R549: **slice 3 must
land before slice 3.1.** The inline `$fields` arm emitters are this item's convergence targets and the
keystone's raw material, so running them concurrently means two items editing the same emitters for
different reasons. Slice 3 first is also the cheaper order, since the keystone then folds arms whose
condition composition is already a one-line call.

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
   non-vacuity and bounding pins land here for the same reason R541 gives: pins over an empty set
   are not enforcers.
2. **Glue renderer, replacing `QueryConditionsGenerator`, root call sites first.** The map-taking
   glue family for every row, with the locals-then-one-call body convention, facet fragments as
   masked variants, FK-target aliasing under the fork-5 scheme. The name lift completes here: the
   four recomputation sites in `TypeFetcherGenerator` and `buildConditionCall`'s formula read minted
   `UnitRef`s, the root fetchers pass `env.getArguments()`, and `conditionMethodName` /
   `facetBaseConditionMethodName` / `facetConditionMethodName` / `CLASS_NAME_SUFFIX` retire. This
   slice owns its own SQL enforcer (see Acceptance) and settles `contextArguments` (implement via
   the env-appending signature with a fixture, or land the `ValidateMojo` deferred rejection) in the
   same commit that deletes the comment documenting the breakage. The family's migration dial closes
   here, windowless: the membership enforcer (the derived fact's true-set equals the relation's
   key-set) lands in the same commit that retires the generator, matching R541's closing slice.
3. **Call-site convergence: every inline fold becomes a glue call.** `SplitRowsMethodEmitter`'s
   field-filter fold, the inline `$fields` arm emitters (`sf.getArguments()` as the map),
   `MultiTablePolymorphicEmitter`'s per-branch folds, `LookupValuesJoinEmitter` and
   `SelectMethodBody`: each stops composing extraction and terms inline and emits the one-line glue
   call, reading the name through the pinned base/view derivation. This is a bounded edit to those
   hosts' condition composition, not a migration of the hosts; hop filters and parent correlation
   stay theirs. `ArgumentValueSource` and the inline uses of `FkTargetConditionEmitter` /
   `ArgCallEmitter` / `declareAliases` retire with it. The execution tier pins the behaviour; the
   readability requirement is what this slice delivers.
4. **The launcher handshake.** `LauncherCommand.where` resolves as the row's glue `UnitRef`
   (jointly with R541 slice 1; see fork 4 for the ordering). The cross-kind edge appears in the edge
   view, typed emitted-or-external, and the plan-time closure check covers it. A covered launcher
   coordinate whose live filter set is empty has no row in this relation; the launcher's `where`
   slot is absent there and its renderer composes the neutral condition, so absence is data rather
   than an inline escape hatch.

## Acceptance

- **SQL equivalence, not byte equivalence, with an enforcer this item owns.** The execution tier
  stays green unchanged, and conjunct order is preserved exactly, so whichever of R541 and R552 lands
  first, the other does not edit its pinned strings. The one sanctioned SQL-text delta is fork 5's
  alias scheme, decided in slice 2 before any pin is authored. A borrowed suite is not this item's
  enforcer: in slice 2, after the alias decision, this family's cases are added to the programme-level
  equivalence harness R549 slice 2 stands up in `graphitron-sakila-example` (the per-test-class
  `SQL_LOG` `ExecuteListener` idiom), asserting exact rendered SQL for one representative faceted, one lifted-outer, one
  FK-target, and one filtered-child coordinate; the child pin is what holds slice 3's convergence to
  "call sites moved, SQL did not". The existing condition execution tests (`GraphQLQueryTest`,
  `MultiTableFilterExecutionTest`, the fixtures in `graphitron-sakila-service`'s conditions package)
  keep pinning behaviour. Where slices 1 to 3 change emitted Java shape (parameter names, extraction
  locals, call sites), SQL does not move.
- **The relation is non-vacuous and correctly bounded.** One condition row per covered
  `(coordinate, resolvedTable)` key: a polymorphic root's row count equals its participant count, and
  a coordinate with an empty live filter set appears zero times. Glue is total, so it needs no
  separate bound. The named exclusions hold: no lookup-key predicates, and the deferred rejections
  that land (non-key lookup filters, and `contextArguments` if slice 2 rejects rather than
  implements) fire on their fixtures.
- **Convergence is structural, not asserted on strings.** Slice 3's observable is retirement: the
  `ArgumentValueSource` type and the inline hosts' uses of the extraction and term machinery are
  deleted, so a host that wanted to compose a fold inline again would have nothing to call; the
  compiler enforces what a body-shape assertion cannot without violating the tier doctrine.
- **The two absorbed defects have fixtures.** R475's sibling same-named filter fields compile. R472's
  nested generated condition fails the build as a deferred rejection rather than emitting a dangling
  reference, and the same fixture flips to compiling and executing when R549 slice 3.1 supplies the
  nested coordinates; both states are pinned, so the transition is a test changing its expectation
  rather than a test appearing.
- **Closure and edges.** The level-1 oracle (`MethodClosureOracleTest`) stays green over emitted
  output. The plan-time edge view covers glue-to-body and glue-to-external edges, and the external
  arm resolves against the same reflection surface `ConditionResolver` uses today. The base/view
  naming pin holds: for every row, the minted `UnitRef`s equal the model-carried
  `(className, methodName)` derivation, until slice 5 retires the fact.
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
- `computeLiftedOuters` and `QueryConditionsGeneratorLiftTest` (slices 2 and 3): the lift dissolves
  into the glue body's one-local-per-argument convention; its two callers
  (`QueryConditionsGenerator`, `MultiTablePolymorphicEmitter`'s branch folds) are both replaced by
  glue calls.
- `ArgumentValueSource` (slice 3): the source fork moves to the call site as
  `env.getArguments()` versus `<sf>.getArguments()`, so the sealed two-variant type and the
  threading through the emitters retire with the last inline fold, together with the inline hosts'
  uses of `FkTargetConditionEmitter` / `ArgCallEmitter` / `declareAliases`.
- `BodyParam.RemoteColumnPredicate` and `FkTargetConditionFilter` as *separate reach expressions*
  (slice 1, conditional on fork 2): the model facts survive until slice-4 re-sourcing, but the
  command expresses both as `Reach.ViaFkPath` and the two EXISTS emitters converge.

## Out of scope

- **The inline hosts as families.** Slice 3 converges their condition *call sites* onto glue, but
  `SplitRowsMethodEmitter`, the inline `$fields` arm emitters, `MultiTablePolymorphicEmitter`,
  `LookupValuesJoinEmitter` and `SelectMethodBody` themselves stay schema-fed generators; their
  command migration is R549 slices 3 and 5. Hop filters, parent correlation and everything else in
  their WHERE composition that is not condition content also stays theirs.
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

## Relationship to existing items

| item | relationship |
|---|---|
| R549 (Spec) | governs; this item is the third proof by argument and the **first by landing order**, running on slice 1 alone. Slice 5's generalisation gate widens from two proofs to three, slice 3b gains its exemplar rows from the entity renderer's GROUP BY, and this item mints the shared term algebra's column reference under the programme's shared-vocabulary rule |
| R541 (Spec) | consumer, and lands after this item. Its fork 1 and this item's fork 4 both resolve at R549: the launcher's `where` slot is the row's glue `UnitRef`, and row 5's naming lift is wholly this item's. Cross-referenced there |
| R333 (Ready) | owns row 5 and the target condition semantics; this item executes the row's "finish lift" verdict in command form and does not touch the `Single` value-gating semantic or the raw-relation re-sourcing |
| R475 (Backlog) | absorbed: slice 1's qualified parameter names are its preferred fix. Tombstone at pickup, delete when this item reaches Done |
| R472 (Backlog) | absorbed, in two steps under the decided ordering: slice 1 converts it to a deferred rejection (the walk does not exist yet), and R549 slice 3.1 supplies the nested coordinates that turn the rejection into an emitted body. Tombstone at pickup, delete when the second step lands |
| R387 (Backlog) | absorbed by slice 1's per-arm renderer tests, which is R549 recipe step 5 applied to this family |
| R334 (Backlog) | absorbed: the glue body's one-local-per-argument convention is its fix for the condition family, and slice 3 removes the ternary chains from every call site. Tombstone at pickup, delete when this item reaches Done |
| R11 (Backlog) | untouched: resolver-side, feeds new binding rows into the same relation when it lands |
| R245 (Backlog) | untouched: mutation conditions join the relation when their emit exists |

