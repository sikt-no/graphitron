---
id: R552
title: "Condition command: the WHERE family as coordinate-keyed condition units"
status: In Progress
bucket: architecture
priority: 4
theme: classification-model
depends-on: [facts-and-commands]
created: 2026-07-27
last-updated: 2026-07-29
---

# Condition command: the WHERE family as coordinate-keyed condition units

Owns seam-worklist row 5 of R333's living table: the `<field>Condition(...)` unit, emitted today by
`TypeConditionsGenerator` and `QueryConditionsGenerator` and recorded there as the one half-migrated
naming edge (regime 1 at the entity end, regime 2 at the shim end, verdict "finish lift"). This item
is **the third leg of R549's proof sequence**: slice 3 (the projection command) proves type grain and
a contribution list, R541 (slice 3c, the launcher command) proves coordinate keying, strategy as
data, and the first cross-command edge, and this item proves the four things neither can reach
(below). It finishes row 5's lift as a command family rather than as a per-edge naming migration,
which dissolves the regime-2 naming locus instead of repointing it.

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
  and its signature is free of graphql-java entirely: a jOOQ table and a `java.util` argument map in,
  one `Condition` out, so the unit is exercised in a test with a record literal and a `HashMap`. The
  `contextArguments` rider is the stated exception: a row taking the env-appending signature carries
  graphql-java in its glue, so this proof is evaluated on the plain rows, and is preserved outright
  if slice 1 lands the rejection instead. That
  tests that the command vocabulary (UnitRef, producer, total renderer) is not select-specific. It is
  also the first
  member of R333's operation crosswalk (the `condition` row) realized as a command; `orderBy` (row 9)
  and the rest of the operation seams will follow this template in R549 slice 5, so a template that
  only fits queries would be found out here.
- **A second cross-kind edge, from the consumer side.** R541 proves launcher-to-projection; this
  relation adds launcher-to-condition, so R549 slice 7's per-kind edge-view union is a union of more
  than one edge kind before slice 5 generalises it. Separately, and as scope coordination rather
  than proof: R541's design fork 1 (how the launcher carries its WHERE without a rendered-code
  escape hatch) dissolves once this relation exists, because the `where` slot becomes the row's glue
  `UnitRef`.
- **External-code edges.** An authored `@condition` names a developer method the emit does not own
  (`ConditionFilter` is a `MethodRef` into consumer code). This is the first command carrying a
  callee outside the emitted set, so the edge view learns the emitted-versus-external distinction
  here, at one arm, before the service-call unit (R333 worklist row 11) needs it at scale. The
  closure oracle's "every callee resolves" splits cleanly: emitted callees resolve against the plan,
  external callees resolve against `ServiceCatalog` reflection, and the command records which kind
  each edge is instead of the renderer knowing.
- **The type-keyed fold gets its exemplar.** R549 slice 3b's one worked example is literally this
  family: "emit a conditions class for a type exactly when some coordinate on it carries a condition
  operation". Today's `<X>Conditions` classes are GROUP BYs over coordinate rows in two schemes (by
  return type at the entity end, by root type at the shim end); the target keeps one, glue classes
  by parent type, so shipping this relation hands slice 3b its first derived
  `(typeName, unitKind)` rows and validates that the fold direction is right before 3b commits to
  it for all 24 predicates.

A fifth property is not unique to this family but lands here first at full strength: **boundary
marshalling as data.** `CallParam` / `CallSiteExtraction` / `BodyParam` are the argument-extraction
vocabulary that crosses the resolve/SQL line (env arguments and nested input paths on one side, typed
Java parameters on the other), and the condition unit is where all of it concentrates. Neither the
projection command (gated on selection, not arguments) nor the root launcher (its arguments arrive
via the condition and pagination slots) exercises it.

The family is also well-bounded the same way R541's is: membership is derived from facts the model
already carries (a coordinate's live filter set is nonempty), with no exemption list anywhere, so
"did we cover it" is decidable.

## Current topology (2026-07-27 code walk; census re-verified 2026-07-28)

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
  `buildConditionCall` re-derives the method name. This is the regime-2 end row 5 wants lifted.
- **Everything else composes the identical fold inline.** The complete
  `FkTargetConditionEmitter.emitTerm` caller set outside the two named-unit generators:
  `SplitRowsMethodEmitter.buildWhereCondition` (child rows methods), `InlineTableFieldEmitter` and
  `InlineLookupTableFieldEmitter` (inline `$fields` arms), `MultiTablePolymorphicEmitter`
  (per-branch WHERE), and `TypeFetcherGenerator.buildQueryLookupRowsMethod` (the root lookup
  coordinate: lookup keys ride the VALUES join, and the method deliberately folds the coordinate's
  non-key filters inline, with `Env` reads and static FK-target aliases). Each seeds
  `DSL.noCondition()` and ANDs the coordinate's field filters through the same `emitTerm` (hop
  filters take their own path, `JoinPathEmitter.emitTwoArgMethodCall` over `hop.filter()`, and
  never reach it), and differs
  only in `ArgumentValueSource` (`Env` versus `FromSelectedField`), alias-prefixing mode, and the
  outer-argument lift (`SplitRowsMethodEmitter` and the inline `$fields` arms pass
  `liftedOuters = null`; `MultiTablePolymorphicEmitter` reuses `computeLiftedOuters` with a
  populated map, the one lift outside the root shims). Two near-misses are excluded so the census
  stays exact: `SelectMethodBody` declares a `condition` local nothing ever ANDs into (its own
  comment says jOOQ folds the `noCondition()` away), and `LookupValuesJoinEmitter` consumes a
  caller-seeded condition local without composing filters of its own (it is R333 row 7's input-rows
  unit). Neither is a condition host, and neither is a convergence target.

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

One coordinate-keyed command relation, one rendering, one consumption rule. The relation, keyed
`(coordinate, resolvedTable)`, carries the ordered predicate list; every row names its **glue
unit**, the emitted method that extracts this coordinate's argument values into named locals and
composes its predicates into one `Condition`: generated terms rendered directly (they are ours to
emit), authored predicates as calls into developer code. Glue classes are a type-keyed GROUP BY over
the rows, one class per parent type. The consumption rule is the owner's stated requirement,
readability and debuggability of the generated output: **every WHERE consumer calls glue; nobody
composes the fold inline.** The root fetcher, the child rows method, the inline `$fields` arm, and
the polymorphic branch all emit the same one-line call,
`<Parent>Conditions.<field>Condition(<alias>, <argsMap>)`, and the extraction ternaries exist in
exactly one generated place per coordinate, as named locals inside the glue body. Node-id decode
helpers dedup along with them, partially: they are per-host-class today
(`CompositeDecodeHelperRegistry.collectInto` builds a fresh registry per `TypeSpec.Builder`, called
from `QueryConditionsGenerator`, `TypeClassGenerator` and `TypeFetcherGenerator`, which is why
`decodeFilmKeysOrThrow` is minted into three generated hosts), and glue hosts are per parent type, so
the two `Language` copies collapse onto `LanguageConditions` while the `Query` copy stays on
`QueryConditions`. The win is one helper per parent type instead of one per host class; the bound is
worth stating so no slice is written against a collapse-to-one the output will not show. Today's
root-only shim was this rule applied to one consumer; this item applies it to all of them. The typed
entity layer (`<ReturnType>Conditions`, the per-participant classes) survives only through the
migration window, feeding not-yet-converged call sites, and retires when the last one converges: once
every consumer calls glue, a second layer whose only caller would be glue itself serves nobody
(fork 1). The regime-2 naming locus dissolves into the naming vocabulary, the one formula both ends
read (see Design).

This is a deliberate strengthening over the previous draft, recorded so the reviewer sees the
reasoning and not just the result. The draft modelled a root-only "boundary" relation; the code walk
below shows the extraction-plus-fold is performed identically at every consuming site (a child rows
method does `.and(FilmConditions.filmsByMixedFilterSplitCondition(f0, decodeFilmKeysOrThrow(
env.getArgument("filter") instanceof Map<?, ?> map1 ? map1.get("ids") : null)))` inline, and the
polymorphic branches even reuse the outer-argument lift), so "boundary" was one consumer's emission
convenience promoted to a command kind. The corrected model makes the glue total, one per condition
row, and the readability requirement is what pays for the extra emitted methods. A second collapse
followed the same review question one step further: with every consumer calling glue, the typed
entity methods' only remaining callers would have been glue itself and the facet masks, so the
two-layer emission is a migration-window artifact rather than an end state, and the dual class
naming scheme (entity by return type, glue by parent type) reduces to one.

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
      /** Graphitron-minted column predicate; its terms render directly in the glue body. */
      record Generated(List<ColumnTerm> terms, List<CallParam> bindings)
          implements Predicate {}
      /** Developer @condition method: an opaque external call, no terms of ours at all. */
      record Authored(MethodRef method, List<CallParam> bindings, List<FkHop> reach)
          implements Predicate {}
  }

  /** A hop the producer has proven FK-derived: `pairs` is `hop.on()` narrowed once, at
      production, so renderers read the pairs directly instead of re-checking the arm. */
  record FkHop(JoinStep.Hop hop, On.ColumnPairs pairs) {}

  /** One comparison. `reach` empty means this row's own table; non-empty means a correlated EXISTS
      over the hop path. `columns.size() > 1` is the row-value form. `nonNull` is the presence-gating
      fact (`BodyParam.nonNull()` today): false means the rendered term is guarded on the value. */
  record ColumnTerm(List<ColumnRef> columns, MatchKind match, boolean nonNull, List<FkHop> reach) {}

  enum MatchKind { EQUALITY, MEMBERSHIP }
  ```

  The glue slot is total, so it is a slot rather than a second relation: a 1:1 derived unit rides
  its row, unlike the previous draft's 0:1 boundary population, which needed a relation of its own
  to satisfy the grain rule. The row's edge set (the external methods its authored predicates call)
  is a total switch over the predicate arms, per R549's edges-are-a-derived-view rule.

- **The glue signature unifies the source fork.** `ArgumentValueSource`'s own javadoc states that
  the generated condition and decode logic are identical at every site and only the
  `getArgument`-shaped read expression forks: `env.getArgument(name)` where the enclosing env is the
  field's own, `<sf>.getArguments().get(name)` at the inline sites where env belongs to an ancestor.
  Both surfaces expose the same coerced `Map<String, Object>`, so the glue takes the map:
  `public static Condition <field>Condition(<JooqTable> table, Map<String, Object> args)`. The
  caller supplies `env.getArguments()` or `<sf>.getArguments()`, the fork moves out of the emit
  machinery and into one call-site expression, and the condition-path uses of
  `ArgumentValueSource` retire with the last inline fold. The type itself is not this family's to
  delete: `RoutineCallEmitter` switches on it for `@routine` IN-parameter binding, reached from
  every alias-declaration loop through `JoinPathEmitter.emitTableExpression`, and that use
  retires with the routine family, not here. Two riders. A coordinate whose authored condition consumes `contextArguments` gets
  `DataFetchingEnvironment env` appended to its glue signature, producer-decided per row; that is
  also what turns the known-broken `contextArguments` emit (the shim calling a `graphitronContext`
  helper it does not have) from a deferred rejection into an implementable slot, since context is
  request-global and the ancestor env at inline sites serves it correctly. And the implementer
  verifies the equivalence `env.getArgument(name)` versus `env.getArguments().get(name)` against
  graphql-java before relying on it; the current tree already treats the two as interchangeable
  across sites, so this is a confirmation, not a gamble.
- **The glue body is the readability contract.** One named local per argument (extraction, decode,
  enum coercion, with the nested-path `instanceof` chains landing on the local's right-hand side),
  then the predicate composition: generated terms with their presence guards, authored predicates as
  calls into developer methods. `films` renders as locals for `rating`, `textRating`,
  `maxRentalRate` followed by three guarded `condition = condition.and(table.RATING.eq(...))` steps,
  everything about the coordinate's WHERE in one method. This is R334's fix for the condition
  family, absorbed here: the ternary chains stop appearing at call sites entirely and stop appearing
  as inline call arguments even within the glue. The outer-argument lift (`computeLiftedOuters`)
  generalises into this convention, since "a shared outer becomes a local" is just the locals rule
  applied to a prefix of the path, and the `JooqConvert` list pre-lift follows the same way. The
  cost this buys into is a mixed body, marshalling then predicates in one method; the previous
  two-layer split separated them, and fork 1 records why the separation lost.
- **Glue hosting generalises today's shim rule instead of inventing one, and the second scheme
  retires.** Glue for coordinate `Parent.field` lives on `<Parent>Conditions` as
  `<field>Condition`; the root family's `QueryConditions` is the existing instance of that rule
  (parent type `Query`), so root output keeps its shape while child and inline coordinates gain
  methods on classes that follow the same formula. `GeneratedUnits.conditions(parentTypeName)`,
  whose javadoc already says "per-parent" while only the shim end honoured it, becomes true as
  stated, and it becomes the *only* scheme: the return-type-keyed `<ReturnType>Conditions` entity
  classes and the per-participant classes exist only through the migration window. Participant rows
  on one coordinate disambiguate by producer-minted name (the producer computes every name, so this
  is a naming-vocabulary decision, not a formula at the emit site).
- **The two schemes share one class-name template in one package, so the window needs a stated
  rule.** Both schemes render `<Type>Conditions` into `<outputPackage>.conditions`, and both
  generators reach it through the same `write(..., "conditions", ...)` call in
  `GraphQLRewriteGenerator.runPipeline`, whose `EmissionLog.record` is a map `put` and whose writer
  writes the path again: a name produced by both schemes clobbers silently, second write wins, and
  the missing half surfaces as `cannot find symbol` at the *consumer's* javac, the failure class this
  item exists to remove. The collision condition is a type that is both the parent of a covered
  coordinate and the return type of one, which is ordinary in a real schema (`Query.films` returning
  `Film` alongside any filtered `Film.*` coordinate). `Query` is safe by construction, since slice 1
  replaces the generator that owns that name; every other parent type is not. Today's fixture set
  escapes by luck rather than by rule: glue parents are `Query`, `Language` and `Store` while the
  entity classes are `Address`, `City`, `Customer`, `FilmActor`, `Film`, `FilmEndorsementNode`,
  `ProjectNote` and `Staff`, disjoint sets with no invariant holding them apart, and one filtered
  `Film.*` or `Customer.*` coordinate closes the gap. **The window rule is slice 1's to decide before
  it emits any non-root glue**, alongside the fork-5 alias decision. Recommendation: when a glue
  class name coincides with an entity class name, the glue renderer folds its methods into that
  class's `TypeSpec` rather than emitting a second file, so one file carries both method sets through
  the window and slice 3 removes the entity half from it; that keeps the promise that root output
  keeps its shape and adds no window-only name. A window-only suffix on the glue class is the
  alternative and costs a rename at slice 3.
- **The sketch borrows, it does not re-mint, and that is R549's allowlist in its first use.** Every
  ref in the record set above resolves to a type that already exists: `ColumnRef`, `TableRef`,
  `MethodRef`, `JoinStep.Hop`, `CallParam` and `CallSiteExtraction` are all in `rewrite/model/`, and
  `Coordinate` is graphql-java's `FieldCoordinates`. Being the first family to land, this item is
  where the vocabulary either stays one set of names or forks into two, so it is stated flatly:
  **this item mints no parallel copy of anything the model already carries.** The earlier draft's
  `Binding`, `ExternalRef`, `FkHopRef` and a `command`-private column reference were all such copies,
  each arriving with a rationale that read reasonably one at a time and would have doubled the
  extraction hierarchy alone by roughly ten arms. `command` imports them under R549's named
  allowlist, which is the migration dial R545 empties into the shared pure-data floor.
- **Four collapses, on R549's rules, worth stating because the earlier sketch had all four.** The
  `Reach` sealed pair is gone: `Local()` was an empty record meaning "no hops", so the slot is the hop
  list and empty is local. `ColumnTerm`'s `Eq` / `In` / `RowEq` / `RowIn` are gone as arms: they are a
  2x2 of (scalar, row) by (equality, membership), row-ness is `columns.size() > 1`, and one record
  plus a two-value `MatchKind` covers all four. `Gate` is gone as a type: the only gating fact is
  `BodyParam.nonNull()`, a boolean, so the slot is `boolean nonNull` on the term; a wrapper type
  would be re-minting a boolean. The model's `BodyParam` keeps its four arms because
  its consumers are different; the command has no reason to mirror a hierarchy it reads from. And the
  column reference itself is `ColumnRef`, the model's, not a condition-private term type, because
  `table.COL` is one SQL shape whether projected or compared and slice 3.1's `SelectTerm` will name
  the same thing. What the item does mint is not a copy of anything: `ConditionCommand`,
  `Predicate`, `ColumnTerm`, `MatchKind`, `FacetFragment` and `FkHop` are command shapes with no
  model counterpart, and every slot inside them is a borrowed ref, a primitive, or one of these
  records. `FkHop` is not the cut `FkHopRef` resurrected: that draft type copied the hop's slots,
  where this one carries the borrowed `JoinStep.Hop` itself plus its `On` arm proven once at
  production, a narrowing pair rather than a parallel copy.
- **Two predicate arms, on one structural axis: who owns the body.** The projection command collapsed
  its provenance distinctions because every arm still rendered to "add these terms"; here one arm
  literally cannot be rendered by us. A `Generated` predicate's terms are ours to render; an
  `Authored` one is an opaque call into consumer code with no terms of ours at all. That single axis
  manifests three ways downstream (the renderer renders terms versus a call, the edge view records
  an external callee only for authored rows, and override suppression reaps only generated rows),
  which is what an arm split has to show. Presence-gating is not part of the split: it is per-term data on the generated
  arm (`BodyParam.nonNull()` today), evidence for the grain note below rather than for the arms.
  One naming heads-up for readers: `Predicate` here shadows the model's `On.Predicate`, and `On`
  is on the same allowlist this sketch imports for the reach slot; the two never meet in one
  expression, and the name is the natural one, so this is a note, not a rename.
- **Reach is a hop list, attached at each arm's own grain.** Today the "predicate over rows reached
  through a join path" shape exists twice under different names: `BodyParam.RemoteColumnPredicate`
  (generated, rendered inside the entity method) and `FkTargetConditionFilter` (authored, rendered
  at the fold site). Both render the same SQL shape, a correlated `DSL.exists(selectOne()
  .from(target).where(correlation.and(inner)))`, so under R549's rule (term arms are SQL shapes,
  never reasons) they are one thing. The grain differs and the command respects it: on the generated
  arm reach is decided per body param today (`FieldBuilder` routes each input binding locally or
  remotely, so one entity method routinely mixes a local equality with a remote EXISTS), so the hop
  list sits on the `ColumnTerm`; on the authored arm the wrap covers the whole call, so it sits on the
  predicate. One narrowing is a genuine capability, and it has a named shape rather than a claim: both EXISTS
  emitters accept only FK-derived hops (`JoinStep.Hop` whose `on()` is `On.ColumnPairs`) and throw
  `IllegalStateException` on anything else, four throws, two per emitter. `JoinStep.Hop` alone
  cannot carry the narrowing (its `on()` still admits `Predicate` and `Lateral`), so reach is a
  list of `FkHop`, the command-local pair of the borrowed hop with its proven `On.ColumnPairs`,
  minted by the producer in the one place that checks the arm; the four render-time throws become
  one produce-time check and the renderers stop branching. The
  single-layer glue also dissolves the placement asymmetry the two-layer draft had to carry: both
  arms' EXISTS render in the glue body, one site, one shape.
- **Bindings are map-relative, and the command reads the model's extraction vocabulary.** `CallParam`
  and `CallSiteExtraction` already describe an extraction; under the glue signature the extraction is
  always rooted at the args map, so bindings need no source axis at all, which is the only thing the
  earlier draft's `Binding` was adding. The home question is decided here rather than discovered at
  implementation, and the earlier answer is reversed: the command carries `CallParam` under R549's
  allowlist rather than re-minting it. `CallSiteExtraction` alone is roughly ten arms including the
  nested-path, enum-coercion and node-id-decode cases, and a parallel copy of it would be the single
  largest piece of duplicated vocabulary in the programme, maintained in two places from this family's
  first row. What the earlier draft was protecting against, a model type inside `command`, is real but
  smaller: it is R545's javapoet problem, it is bounded by the allowlist, and it resolves by moving
  these types to the shared floor rather than by copying them. The extraction and term machinery
  (`FkTargetConditionEmitter`, `ArgCallEmitter`) is re-parameterised over the command arms and becomes
  internal to the glue renderer; the call-site convergence slice removes the last inline users, so no
  `WhereFilter` adapter and no coexistence emitter is needed, and unmigrated hosts interact with this
  family only by emitting a one-line call to a name.
- **Suppression stays where it is resolved.** Union-then-suppress (R333's `generated_op ... is live
  iff` rule) is already computed in `FieldBuilder.projectFilters`, and a suppressed generated
  predicate is already absent from `filters()`. The producer reads the resolved lists, per R549's
  "slice 3 needs no fact walk" discipline: production reads exactly where the generators read today,
  and re-sourcing onto the three raw relations (`generated_condition` / `authored_condition` /
  `consumes`) is slice 4 territory. No suppression logic moves in this item.
- **Extraction locals are producer-named, and that dissolves R475.** R475's bug is the entity
  method's Java parameter list keying on the bare input-field name, which collides across sibling
  arguments and ships uncompilable output. In the single-layer glue there is no generated parameter
  list to collide: arguments become body locals, and the producer computes qualified,
  collision-free local names as data on the binding rows. The fixture R475 describes compiles once
  the entity layer retires (its emission is untouched through the migration window, so the fixture
  lands with slice 3). This is part of the item's capability payload, per R549's "no slice that is
  purely a migration payment".
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
  SQL effect). Two edge cases are named rather than glossed. Lookup coordinates: lookup keys go through the
  VALUES join and are not predicates, so the producer keeps them out by the fact; the coordinate's
  *non-key* filters then split by arm, and the rejection is scoped to the arm that is actually
  broken. Authored `@condition` entries are composed today
  (`TypeFetcherGenerator.buildQueryLookupRowsMethod` deliberately folds them inline), so they
  become ordinary rows in this relation and converge on glue like any other consumer; rejecting
  them would convert implemented, working emit into a build failure. A *generated column* filter
  mixed onto a lookup coordinate is the genuinely unemitted case (`TypeConditionsGenerator` skips
  every `LookupField` with an in-source "no such schema exists today" note), and that narrow case
  becomes a `ValidateMojo` deferred rejection instead of a silent skip. `@condition(contextArguments:)`: the emit is known-broken today (the shim emits
  a call to a `graphitronContext(env)` helper the class does not have, documented only in a source
  comment slice 1 deletes); the env-appending glue signature makes it implementable, so slice 1
  either implements it with a fixture or lands the `ValidateMojo` deferred rejection, and the one
  option that is already wrong is inheriting the silent breakage. Stating the key up front is what
  keeps "exactly one row per key" structural instead of quietly false for interface and union
  roots: a participant row differs from its siblings in its `resolvedTable`, so the expansion needs
  no second key column.
- **Facet fragments are masked glue variants, not new commands.** Which fragments exist
  (`<field>FacetBaseCondition`, one `<field>Facet_<g>Condition` per facet input) and which parameter
  slots each masks to `null` are producer decisions carried on the row, present exactly when the
  coordinate carries facet inputs (gated by a fact, so the slot-implies-slot coupling the previous
  draft had is gone); the glue renderer emits them from the same predicate list. Each fragment
  carries its own minted `UnitRef` alongside its mask, which is the shape R541's connection carrier
  plan consumes across the slice-4 handshake. Today that
  knowledge is generator control flow in `buildSuppressedConditionMethod`; as data it becomes
  assertable in the pipeline tier.
- **Conjunct order is preserved, exactly.** Today's fold appends the generated predicate first, then
  authored conditions in argument order (`FieldBuilder.projectFilters` builds the list that way), and
  the command keeps that order verbatim rather than normalising to SDL order. Reordering conjuncts is
  semantics-preserving but changes rendered SQL text, and R541's acceptance pins exact SQL strings;
  an item whose promise is "shape may move, SQL may not" does not spend its budget on a cosmetic
  reorder.
- **One renderer, one kind, and the old generators wind down on a stated schedule.** The glue
  renderer groups rows by parent type and emits the glue classes (this is the type-keyed GROUP BY,
  slice 3b's exemplar), total over the relation with nothing to branch on, taking no
  `GraphitronSchema` (R549 invariant 1), living in `render`. `QueryConditionsGenerator` is replaced
  by it in slice 1; `TypeConditionsGenerator` keeps emitting the entity layer untouched through the
  window, because not-yet-converged call sites still name it, and is deleted in slice 3 when the
  last caller converges; both retirements decrement invariant 1's ratchet. The fold shape itself
  (seed, guarded AND, single-predicate short form) is renderer-internal. `computeLiftedOuters`
  dissolves into the locals convention rather than moving: its two callers
  (`QueryConditionsGenerator` and `MultiTablePolymorphicEmitter`'s branch folds) are both replaced
  by glue, and `QueryConditionsGeneratorLiftTest` retires with it.
- **Naming is one formula, and it already has a home.** The producer mints every glue `UnitRef`
  (class by parent type, method by field, fragment names, participant disambiguation) from the
  naming vocabulary R549 slice 1 moves out of `compile/`, and `GeneratedUnits.conditions
  (parentTypeName)` is that formula's existing statement. The window's second readers read the same
  vocabulary, not a parallel formula: the four `TypeFetcherGenerator` sites that recompute the shim
  FQCN plus `buildConditionCall`'s method-name formula switch to minted `UnitRef`s in slice 1, and
  slice 2's convergence call sites, which live in unmigrated schema-fed generators that cannot read
  the plan, derive the glue name through the same `GeneratedUnits` accessor, so producer and call
  sites cannot disagree without one of them abandoning the shared vocabulary. The entity layer's
  naming facts (`GeneratedConditionFilter.className()` / `methodName()`, minted in
  `FieldBuilder.projectFilters`) stay untouched feeding `TypeConditionsGenerator` through the window
  and retire with it in slice 3. The `+ "Condition"` grep from R333 thread I then finds one formula
  in one home.

## Design forks for the Spec reviewer

1. **One-layer or two-layer emission.** Today's shape is two layers: typed entity methods (null
   guards over typed values, hosted by return type) and the fold (extraction plus composition,
   hosted by parent type). Recommendation: **one layer**, the glue, with the entity layer retiring
   when convergence removes its last external caller. The two-layer arguments were tested and fell.
   Independent assertability transfers: glue's signature is a jOOQ table and a `java.util` map, so
   it is exactly as constructible in a test as the typed method was, and it exercises extraction,
   gating and predicates together. The facet masks do not need it: a masked *generated* term is
   statically omitted from the fragment body, which renders the same SQL as passing `null` into a
   guard, while masked *authored* calls keep their runtime `null` literals because an opaque
   developer method must still be called. And in the end state the entity method's only caller
   would be glue itself, so the layer separates marshalling from predicates for no reader while
   forcing every human to hop between two classes in two naming schemes to read one coordinate's
   WHERE. What the collapse buys beyond that: the dual class-naming scheme reduces to one, R475
   dissolves instead of being patched (fixed parameter lists become producer-named locals), and the
   `CallParam` / `BodyParam` pairing pressure in the model loses its structural cause. The honest
   costs: a mixed body (marshalling then predicates in one method); a migration window in which
   `TypeConditionsGenerator` still emits the entity layer for not-yet-converged callers, so the
   guarded-AND fold over generated terms exists twice through slices 1 to 3 (there and in the glue
   renderer), the duplication the slice-1 implementer meets first and slice 3 deletes; the two
   schemes sharing one `<Type>Conditions` name template in one package for the length of that
   window, which is the hosting rule stated in Design and needs deciding before the first non-root
   glue class is emitted; and churn of the generated conditions package, bounded to that package
   and SQL-neutral.
2. **Reach unification.** Fold `RemoteColumnPredicate` and `FkTargetConditionFilter` into one
   FK-hop-list reach slot (recommended above), or keep two expressions mirroring today's types.
   Recommendation: unify. The single-layer glue removed the old risk (the two EXISTS forms rendered
   at different sites; now both render in the glue body); what remains to watch is the grain mapping,
   per-term on the generated arm and per-predicate on the authored arm, and if that mapping fights in
   practice the implementer says so in the In Review hand-off.
3. **Is the R475 fix in scope?** Recommendation: yes, and under the single layer it is a dissolution
   rather than a fix: there is no generated parameter list left to collide, and the producer names
   the extraction locals with qualified, collision-free names. The alternative (rejecting
   sibling-name collisions at classify time) would reject a working schema shape for an emitter
   limitation the reframing removes for free. The fixture compiles once the entity layer retires
   (slice 3), since the colliding method is emitted unchanged through the window.
4. **Who owns the launcher handshake. Resolved 2026-07-27, at the programme.** This fork and R541's
   fork 1 were each recommending the same answer while deferring the sequencing to the other item's
   reviewer, so neither closed. R549's Slices section owns the ordering and records the reasons:
   **this item lands first**, and R541 consumes rather than builds. What is decided here, because it
   is this item's to decide: R552 owns condition production wholesale, and R541's `where` slot is the
   row's glue `UnitRef`. The premise correction that shrank R541's side (its root builders already
   call named `<field>Condition` methods, so nothing there was ever new condition machinery) is
   recorded at R541 fork 1.
5. **The FK-target alias scheme inside glue.** Today the EXISTS aliases fork by host: static
   `table_fkt<f>_<h>` locals at the shim, runtime-prefixed (`<base>.getName() + "_fkt..."`) at the
   recursion-prone inline sites. Glue methods are per-coordinate scopes, but two glue methods land
   in one query on polymorphic roots (one per participant branch of the UNION), so static names can
   collide across branches. Options: runtime-prefixed everywhere (uniform, matches the inline
   convention, changes the root family's rendered alias text), or a static per-row prefix derived
   from the participant (keeps aliases static and unique, one more naming rule). Recommendation:
   runtime-prefixed everywhere; one convention beats two, and the inline sites already prove it.
   Either way the decision lands in slice 1 *before* the SQL pins are authored, so the pins never
   move afterwards; alias text is the one place this item's SQL is allowed to differ from today's,
   and the pin suite is written against the post-decision strings.

## Slices

Slice 0 is **R549 slice 1**, the vocabulary skeleton; nothing else in the keystone is a prerequisite
here. The landing order among the three proofs is R549's Slices section's to state, which is why the
sequencing question in fork 4 is closed rather than open.

R549 slice 3.1 is a **soft** dependency of exactly one thing: the nested-coordinate walk that fixes
R472. Promoting nesting types to projection units is what gives a nested `GeneratedConditionFilter` a
walkable home; without it, the producer cannot see those coordinates and R472 converts to a deferred
rejection instead of a fix, per the R472 note in Design. Everything else here runs against R549 slices 1 and 2 alone: slice 1
for the vocabulary skeleton, slice 2 for the programme-level equivalence harness the slice-1 SQL
pins extend.

One hard ordering constraint runs the other way: **this item's slice 2 (call-site convergence) must
land before R549 slice 3.1**, because both edit the inline `$fields` arm emitters. The constraint and
its reasoning live with the rest of the sequencing in R549's Slices section.

1. **Command, producer, glue renderer, root cutover, together.** The record set, the producer
   minting rows for every covered `(coordinate, resolvedTable)` key (participants expanded, nested
   coordinates per the R472 note), and the glue renderer replacing
   `QueryConditionsGenerator`: single-layer bodies under the locals convention, facet fragments as
   masked variants, FK-target aliasing under the fork-5 scheme, decided here before the SQL pins are
   authored. The name lift completes for the root family: the four recomputation sites in
   `TypeFetcherGenerator` and `buildConditionCall`'s formula read minted `UnitRef`s, the root
   fetchers pass `env.getArguments()`, and `conditionMethodName` / `facetBaseConditionMethodName` /
   `facetConditionMethodName` / `CLASS_NAME_SUFFIX` retire. The slice states which rows it renders
   glue for: root rows only, or every row with the non-root glue unreferenced until slice 2
   converges its callers. That is what opens the dual-scheme window, so the hosting rule in Design
   is decided in whichever of the two this slice picks. `contextArguments` is settled here
   (implement via the env-appending signature with a fixture, or land the `ValidateMojo` deferred
   rejection) in the same commit that deletes the comment documenting the breakage. Per R549's
   recipe step 5, the two test surfaces land with it: pipeline-tier assertions on produced rows over
   existing fixtures, and per-arm renderer unit tests asserting unit identity, locals, and
   arm-coverage totality, never `code().toString()` shapes; body correctness stays at the
   compilation and execution tiers. The relation's non-vacuity and bounding pins land here too:
   pins over an empty set are not enforcers. The existing tests this slice rewrites, named so the
   churn is budgeted rather than discovered: four call `QueryConditionsGenerator.generate` directly
   (`QueryConditionsPipelineTest`, `FacetEmitterTest`, `NodeIdOverrideConditionFkTargetPipelineTest`,
   `MultiSchemaPipelineTest`), and `QueryConditionsGeneratorLiftTest` calls `computeLiftedOuters`
   five times, so it relocates with the lift rather than being edited. Three more are
   `{@link QueryConditionsGenerator}` javadoc references that fail the Javadoc reference gate at
   `verify` rather than at compilation, one of them in main sources
   (`CompositeDecodeHelperRegistry`, plus `CompositeDecodeHelperRegistryTest` and
   `NodeIdReferenceFilterPipelineTest`); `-Pquick` skips that gate, so an inner loop stays green
   through the slice and the failure arrives on the first full build. `TypeConditionsGenerator` is
   untouched; the entity layer keeps feeding the not-yet-converged call sites.
2. **Call-site convergence: every inline fold becomes a glue call.** `SplitRowsMethodEmitter`'s
   field-filter fold, the inline `$fields` arm emitters (`sf.getArguments()` as the map),
   `MultiTablePolymorphicEmitter`'s per-branch folds, and
   `TypeFetcherGenerator.buildQueryLookupRowsMethod`'s non-key fold (`env.getArguments()` as the
   map, with a fixture added for the authored-condition-on-lookup shape, which no fixture exercises
   today): each stops composing extraction and terms inline and emits the one-line glue
   call, deriving the name through the shared `GeneratedUnits` vocabulary. This is a bounded edit to
   those hosts' condition composition, not a migration of the hosts; hop filters and parent
   correlation stay theirs. `FkTargetConditionEmitter` deletes in full with this slice (its public
   API, `emitTerm` and `declareAliases`, has no caller left once slice 1 replaced the shim end and
   the last host converges; one javadoc mention repoints), the inline uses of `ArgCallEmitter`
   retire, and the condition-path uses of `ArgumentValueSource` go with them. The execution tier pins the behaviour; the
   readability requirement is what this slice delivers.
3. **Entity-layer retirement, and the dial closes.** With no caller left, `TypeConditionsGenerator`
   and the `<ReturnType>Conditions` / `<Participant>Conditions` classes are deleted,
   `GeneratedConditionFilter.className()` / `methodName()` retire from the model, and because those
   two are declared on the sealed `WhereFilter` itself and read generically by the fold, the abstract
   declarations retire with them (authored arms already carry their name in a `MethodRef`), along
   with the interface javadoc's `{@link}` to the deleted generator;
   `TypeConditionsGeneratorTest` retires (completing R387), and the R475 fixture compiles. Two
   further tests name `TypeConditionsGenerator` and so fall out here rather than in slice 1:
   `FetcherPipelineTest` calls its `generate`, and `ReferenceFilterRemoteColumnPipelineTest` points
   at `TypeConditionsGeneratorTest` in prose. The membership enforcer (the derived fact's true-set
   equals the relation's key-set) lands in the
   same commit, closing the family's migration dial windowless, matching R541's closing slice.
4. **The launcher handshake.** `LauncherCommand.where` resolves as the row's glue `UnitRef`
   (jointly with R541 slice 1; see fork 4 for the ordering), and the handshake is wider than
   `where` alone: R541's resolved fork 5 puts the connection carrier plan on its `ConnectionResult`
   arm, sourced from this row's `facets` slot (the base fragment plus the per-facet fragments), so
   the fragment `UnitRef`s cross the seam too and R541's carrier slice consumes them. The
   cross-kind edges appear in the edge view, and the plan-time closure check covers them. A covered
   launcher coordinate whose live filter set is empty has no row in this relation; the launcher's
   `where` slot is absent there and its renderer composes the neutral condition, so absence is data
   rather than an inline escape hatch.

## Acceptance

- **SQL equivalence, not byte equivalence, with an enforcer this item owns.** The execution tier
  stays green unchanged, and conjunct order is preserved exactly, so whichever of R541 and R552 lands
  first, the other does not edit its pinned strings. The one sanctioned SQL-text delta is fork 5's
  alias scheme, decided in slice 1 before any pin is authored. A borrowed suite is not this item's
  enforcer: in slice 1, after the alias decision, this family's cases are added to the
  programme-level equivalence harness R549 slice 2 stands up in `graphitron-sakila-example` (the
  per-test-class `SQL_LOG` `ExecuteListener` idiom), asserting exact rendered SQL for one
  representative faceted, one lifted-outer, one FK-target, and one filtered-child coordinate; the
  child pin is what holds slice 2's convergence to
  "call sites moved, SQL did not". The existing condition execution tests (`GraphQLQueryTest`,
  `MultiTableFilterExecutionTest`, the fixtures in `graphitron-sakila-service`'s conditions package)
  keep pinning behaviour. Where slices 1 to 3 change emitted Java shape (glue bodies, extraction
  locals, call sites, retired classes), SQL does not move.
- **The relation is non-vacuous and correctly bounded.** One condition row per covered
  `(coordinate, resolvedTable)` key: a polymorphic root's row count equals its participant count, and
  a coordinate with an empty live filter set appears zero times. Glue is total, so it needs no
  separate bound. The named exclusions hold: no lookup-key predicates, and the deferred rejections
  that land (generated column filters mixed onto lookup coordinates, and `contextArguments` if
  slice 1 rejects rather than implements) fire on their fixtures; authored conditions on lookup
  coordinates stay emitted, as rows in the relation.
- **Convergence is structural, not asserted on strings.** Slice 2's observable is retirement:
  `FkTargetConditionEmitter` is deleted in full, so a host that wanted to compose a fold inline
  again would have nothing to call; the compiler enforces what a body-shape assertion cannot
  without violating the tier doctrine. The condition-path uses of `ArgumentValueSource` and the
  inline hosts' extraction machinery go with it (the type itself survives for routine
  IN-parameter binding; see the glue-signature note in Design).
- **The absorbed defects have fixtures.** R472's nested generated condition fails the build as a
  deferred rejection rather than emitting a dangling reference, and the same fixture flips to
  compiling and executing when R549 slice 3.1 supplies the nested coordinates; both states are
  pinned, so the transition is a test changing its expectation rather than a test appearing. R475's
  sibling same-named filter fields compile once slice 3 retires the entity layer. R334's shape is
  delivered by construction: extraction exists only as named locals in glue bodies.
- **Closure and edges.** The level-1 oracle (`MethodClosureOracleTest`) stays green over emitted
  output. The plan-time edge view covers the external-callee edges, resolving against the same
  reflection surface `ConditionResolver` uses today, and every glue `UnitRef` and every convergence
  call site derive from the one `GeneratedUnits` formula, so the two ends cannot drift apart
  without abandoning the shared vocabulary.
- **The seam worklist records the verdict.** Per R549 recipe step 6, R333's row 5 and its `condition`
  crosswalk row are updated with the landed verdict when this item ships, so the model item and the
  programme cannot drift on what happened to the seam.
- **Third-proof findings are written down.** The In Review hand-off states whether the value-shaped
  unit fit the command template, whether the reach unification held (fork 2's placement switch),
  whether the external-callee edge shape worked, and whether the type-keyed GROUP BY validated slice
  3b's direction. Being the first family, it owes one more: **whether borrowing the model's ref
  vocabulary under R549's allowlist held**, or whether some type genuinely had to be copied into
  `command`. That answer sets the pattern for every family after it, and a copy made quietly here
  becomes the programme's second vocabulary by default. R549 slice 5 generalises from the three proofs together; a hand-off that reports
  nothing has not been read carefully.

## Slice log

### Slice 1 (2026-07-28): command, producer, glue renderer, root cutover

Landed as one commit: the record set in `command/` (`ConditionCommand`, `Predicate` with its
`Generated` / `Authored` arms, `ColumnTerm`, `MatchKind`, `FkHop`, `FacetFragment`), the producer
(`plan/ConditionCommands`, riding `EmitPlan` as a `ConditionRelation`), the glue renderer
(`render/ConditionGlueRenderer`), and the root cutover: `QueryConditionsGenerator` deleted, the
four recomputation sites and `buildConditionCall`'s formula read minted refs, root fetchers pass
`env.getArguments()`, and `conditionMethodName` / `facetBaseConditionMethodName` /
`facetConditionMethodName` / `CLASS_NAME_SUFFIX` retired. `computeLiftedOuters` relocated to
`MultiTablePolymorphicEmitter` (its last inline caller) and the lift test with it. Invariant 1's
ratchet moved: entry points 24 to 23, generator leaf `instanceof` 104 to 100, plan leaf
references 1 to 10 (the relocated coordinate dispatch, deliberately).

**Which rows render: root rows only, and the restriction lives in the plan.** The producer mints
the full relation (participants expanded, lookup and child coordinates included; pipeline pins
cover each population), and commits glue for exactly the coordinates whose fetchers already call
a conditions method (`ConditionCommands.rendersIntoConditionsClass`, root `QueryTableField` +
`QueryTableInterfaceField`). The renderer is total over the rows it is handed and the shell folds
blindly; the committed set is the migration dial, and slice 3's closing enforcer is committed set
equals relation. Consequence found in implementation: the retired shim emitted
`return DSL.noCondition();` methods for filterless coordinates and every root fetcher called
them; under absence-is-data those shims stop existing and filterless fetchers compose the
neutral condition inline, exactly as the Target section promised.

**Fork 5 resolved: runtime-prefixed SQL aliases everywhere in glue** (`table.getName() +
"_fkt<p>_<h>"`, and the generated remote-reach aliases likewise). No pinned string anywhere
carried the old static aliases, so the sanctioned alias delta broke nothing; the new SQL pins are
authored against the post-decision strings.

**`contextArguments` resolved: the deferred rejection, scoped on the fact.** The validator
rejects a committed-coordinate condition whose bindings read the request context, reading the
producer's own committed-set predicate rather than a parallel host list, so the rejection widens
in lockstep as convergence commits more rows and deletes in one place if the env-appending
signature ever lands. The known-broken emit comment died with its generator. The value-shaped
proof is preserved outright: every glue signature is `(JooqTable, Map<String, Object>)`, and the
`env.getArgument` versus `getArguments().get` equivalence was verified against graphql-java 25.0
(`DataFetchingEnvironmentImpl.getArgument` compiles to `arguments.get(name)`).

**The dual-scheme window rule, decided:** when a glue class name coincides with an entity class
name, the glue renderer folds its methods into that class's `TypeSpec` rather than emitting a
second file (implemented at the convergence slice, where non-root glue first renders). Landed
now as its backstop: `EmissionLog.record` fails hard on a duplicate landing address, so a
collision in the window is a build failure instead of a silent clobber surfacing at the
consumer's javac.

**The borrowed-vocabulary verdict (the hand-off obligation): the borrow held; nothing was
copied.** Two shapes the sketch did not have, both borrow-plus-producer-data pairs like `FkHop`,
recorded as findings against the five-type budget: `ArgBinding(CallParam, localName)` (the
binding invariant's carrier; the local-name uniqueness rule is a compact-constructor failure, one
local one value, which is the sibling-name-collision dissolution made mechanical) and
`OuterLift(outerArgName, localName)` (the shared-outer lift as method-grain data). A third,
`UnitMethodRef(UnitRef, methodName)`, exists because `UnitRef` is class-grained and the glue slot
is method-grained; `GeneratedUnits` gained the method schemes (`conditionMethod`,
`facetBaseConditionMethod`, `facetConditionMethod`, `participantConditionMethod`) and the minting
pin covers it, so the method-name formula has one home and slice 2's convergence sites read it.
Bindings ride the terms (a `ColumnTerm` carries its `ArgBinding`), not a sibling list, so the
`CallParam`/`BodyParam` positional pairing is not re-created in `command`.

**One structural correction to the triangle, consulted and adopted:** the flat "render never
imports the model" rule was unsatisfiable for any borrowing family, since the renderer of rows
that carry borrowed refs must read those refs. `PackageImportDirectionTest` now has one borrow
dial both `command` and `render` read (with `HelperRef` added: it rides
`CallSiteExtraction.NodeIdDecodeKeys`, surface the enumeration already implicitly admitted), plus
a reflection-computed closure census pinning the eighteen legacy types the dial's components
transitively admit, so a component addition is a deliberate census edit rather than silent
widening. `CompositeDecodeHelperRegistry` moved to `render/` as declared helper-drain machinery
(legacy hosts import it back), and the two proven-FK join fragments moved to
`render/JoinFragments` with `JoinPathEmitter` delegating, keeping one derivation.

**Facet fragments as data, one behaviour carried over deliberately:** an authored `@condition`
consuming a facet's input field is emitted unmasked in the base fragment and dropped wholesale
from the per-facet fragment, exactly what the retired emitter's `instanceof`-gated rebuild did;
as producer data this is now a visible decision rather than an emitter accident. The fragment
partition (base plus per-facet generated terms equal the row's, pairwise disjoint) is a
constructor failure, and static omission's SQL-equivalence to the runtime null-guard rests on
facet bindings being guaranteed nullable (the classify-time facet-misuse rejection).

Deferred rejections landed with fixture-pinned messages: env-bound bindings on committed
coordinates (above), generated column filters on lookup coordinates (the entity layer skips
`LookupField`, so the emitted call could never compile; authored lookup conditions stay accepted
as ordinary uncommitted rows), and generated column filters on nested fields (the two-step
absorption's first step; the same fixture flips to a produced row when nesting types become
walkable).

Deliberately not built: no coexistence emitter or `WhereFilter` adapter (unmigrated hosts keep
their inline folds untouched until slice 2), no entity-layer edits (`TypeConditionsGenerator`
emits unchanged through the window; the guarded-AND fold now exists twice, there and in the glue
renderer, until slice 3 deletes the entity half), and no re-sourcing (production reads
`filters()` / `participantFilters()` exactly where the generators read).

### Slices 2 and 3 (2026-07-29): call-site convergence and entity-layer retirement, one landing

Landed as one commit, deliberately merging the planned slices 2 and 3: after convergence the
entity layer had zero callers, so the dual-scheme window rule's merge machinery (fold a
coinciding glue class into the entity `TypeSpec`) would have been built only to be deleted one
slice later. Retiring `TypeConditionsGenerator` in the same commit deleted the window, the merge,
and the duplicate-landing-address risk together; the `EmissionLog` hard failure stays as the
general backstop. Every inline fold is now one glue call (`ConditionGlueCall`, the shared
call-expression emitter all five hosts and the facet-plan block read): the split rows methods and
the lookup rows method pass `env.getArguments()`, the two inline `$fields` emitters pass
`<sf>.getArguments()`, and the polymorphic branch folds call their participant-minted methods.
`FkTargetConditionEmitter` deleted in full (the acceptance's structural convergence enforcer),
with `computeLiftedOuters`, the branch-fold plumbing, the inline `JooqConvert` pre-lifts, the
condition-path `ArgumentValueSource` uses (the type survives on the routine path), and the
condition surface of `ArgCallEmitter` (now the `@service`-call argument emitter alone).
`TypeConditionsGeneratorTest` and the lift test retired with their subjects. Ratchets:
entry points 23 to 22, generator leaf `instanceof` 100 to 97, `case` 89 to 87, plan leaf
references 10 to 6.

**The `contextArguments` premise was wrong, and correcting it decided the fork.** Slice 1's log
argued the env-appending signature would preserve a working inline capability; the code says
otherwise: the two `$fields`-hosted inline sites passed a throwaway emission context whose
recorded `graphitronContext` need nothing ever drained ({@code TypeClassGenerator} emits no such
helper), so an inline `@condition(contextArguments:)` was the shim's missing-helper bug a second
time, unpinned by any fixture. Convergence makes the glue class the single host for the read and
a class that can own its helper, so the env-appending signature was implemented rather than the
rejection widened: `CallParam.readsRequestContext()` is the leaf fact,
`WhereFilter.anyReadRequestContext` the one fold (read by `SqlGeneratingField`'s default, the
producer via `ConditionCommand.readsRequestContext()`, and every call site through
`ConditionGlueCall`), the fork is row-grained (a coordinate's glue method and facet fragments
agree), and the glue class's `graphitronContext(env)` helper is emitted through a declared-drain
collector (`render/RequestContextHelper`) so the call and its helper cannot separate again. The
validator's env-bound rejection deleted; sakila pins the root and batched-child round trips end
to end.

**Producer membership is one capability read.** The per-leaf switch became
`SqlGeneratingField` + nonempty filters (a new SQL-generating variant needs no producer edit),
with identity arms only where the filter surface genuinely lives elsewhere: the polymorphic
roots (`participantFilters()`, one row per participant table) and the nesting recursion. Nested
coordinates now produce rows (the walk recurses `NestingField.nestedFields()`; authored-only
until nesting types become walkable, and the nested-generated rejection stands), deduplicated by
key with a hard failure on divergence across nesting reuse sites; the validator's nested-shape
comparison gained the matching filter clause, replacing a comment that declared per-parent
filters deliberately uncompared. The membership enforcer moved up from the closing slice to land
here, where a membership gap first becomes uncompilable output: `ConditionMembershipTest` pins
relation key-set equals an independently derived covered set. `ConditionRelation` collapsed to
`rows`/`units` (committed == rows would have been a restatement), and
`rendersIntoConditionsClass` retired with the rejection that read it.

**A fourth deferred rejection, from a silent-wrong-data find:** `ChildField.TableInterfaceField`
carries the shared filter components but its fetcher folds none of them, so an accepted filter
was silently ignored at runtime; rejected now (authored and generated alike), producer
backstop-throw included, membership rule untouched (no exemption list: a valid schema has no
such row). Implementing that fold is future work, not this item's.

**Second-order moves.** The compile graph's condition edges repointed with the emission: inline
filter edges target the coordinate's glue unit (the entity `className()` edge died with the
slot), the decode-helper and context-helper references ride the glue unit, and participant
filters edge the polymorphic fetcher to its parent's conditions class.
`CallParam.emitsUncheckedCast` / `emitsUncheckedCastFromSelectedField` retired callerless (the
renderer's own cast predicate is the single home; the `$fields` and branch-fold suppression
stamps deleted with the casts they suppressed). `GeneratedConditionFilter.className()` /
`methodName()` and the `WhereFilter` abstract declarations retired;
`FieldBuilder.projectFilters` stopped minting the regime-1 names, closing R333 row 5's lift on
both ends. SQL equivalence held: all seven baseline pins byte-identical across the convergence
(the three new pre-move pins were authored against the inline folds first, per review-before-move
discipline: authored-on-lookup, nested-authored, and the batched context-bound coordinate), and
the sibling same-named filter fixture compiles and executes, closing the collision dissolution
end to end.

## Retired vocabulary

- `QueryConditionsGenerator` (slice 1): replaced by the glue renderer; with it
  `conditionMethodName` / `facetBaseConditionMethodName` / `facetConditionMethodName` /
  `CLASS_NAME_SUFFIX` (the regime-2 formula locus and the public constant its consumers read), the
  shim-FQCN recomputation in `TypeFetcherGenerator` (four sites), and the method-name formula in
  `buildConditionCall`: call sites read the minted `UnitRef`. Three `{@link}` references to the
  deleted generator repoint in the same commit, since the Javadoc reference gate fails the build on
  them: `CompositeDecodeHelperRegistry` (main sources), `CompositeDecodeHelperRegistryTest` and
  `NodeIdReferenceFilterPipelineTest`.
- `computeLiftedOuters` and `QueryConditionsGeneratorLiftTest` (slices 1 and 2): the lift dissolves
  into the glue body's one-local-per-argument convention; its two callers
  (`QueryConditionsGenerator`, `MultiTablePolymorphicEmitter`'s branch folds) are both replaced by
  glue calls.
- `FkTargetConditionEmitter`, the whole class (slice 2): its public API (`emitTerm`,
  `declareAliases`) is called only by `QueryConditionsGenerator`, replaced in slice 1, and the
  convergence hosts, so the class deletes when the last host converges and the compiler enforces
  the consumption rule. Three `{@link}` references repoint in the same commit, because the Javadoc
  reference gate fails the build on a dangling one: two in `MultiTablePolymorphicEmitter` (the
  `declareAliases` and `emitTerm` links in its alias and per-branch-fold javadoc) and one in
  `TypeConditionsGenerator`, which this slice deliberately keeps alive, so that repoint is the one
  edit slice 2 makes to a class it otherwise does not touch. Three further mentions are `{@code}` or
  plain comments and are ungated but stale: `CompileDependencyGraphBuilder`,
  `GraphitronSchemaValidator`, `FieldBuilder`, plus one in a `graphitron-sakila-service` fixture
  javadoc. The inline hosts' uses of `ArgCallEmitter` retire with it.
- `ArgumentValueSource`, condition-path uses only (slice 2): the source fork moves to the call
  site as `env.getArguments()` versus `<sf>.getArguments()`, so the condition emitters stop
  threading it. The sealed type itself survives: `RoutineCallEmitter` switches on it for
  `@routine` IN-parameter binding, a non-condition path, and that use retires with the routine
  family.
- `TypeConditionsGenerator`, the `<ReturnType>Conditions` and `<Participant>Conditions` entity
  classes, `GeneratedConditionFilter.className()` / `methodName()` *together with their abstract
  declarations on the sealed `WhereFilter`*, and `TypeConditionsGeneratorTest` (slice 3): the entity
  layer and its naming facts, deleted when the last converged call site stops naming them.
  `FetcherPipelineTest`'s `TypeConditionsGenerator.generate` call goes here too. Together with
  slice 1 this is two decrements on R549 invariant 1's ratchet.
- `BodyParam.RemoteColumnPredicate` and `FkTargetConditionFilter` as *separate reach expressions*
  (slice 1, conditional on fork 2): the model facts survive until slice-4 re-sourcing, but the
  command expresses both as an FK-hop-list reach slot and the two EXISTS emitters converge.

## Out of scope

- **The inline hosts as families.** Slice 2 converges their condition *call sites* onto glue, but
  `SplitRowsMethodEmitter`, the inline `$fields` arm emitters, `MultiTablePolymorphicEmitter` and
  `TypeFetcherGenerator`'s root builders themselves stay schema-fed generators; their
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
| R549 (In Progress; slices 1 and 2 landed 2026-07-28) | governs; this item is the third proof by argument and the **first by landing order**, running on R549 slices 1 and 2 alone, both of which have landed, so nothing upstream blocks pickup. Slice 5's generalisation gate widens from two proofs to three, slice 3b gains its exemplar rows from the glue renderer's GROUP BY, and this item is the allowlist's first use, borrowing the model's refs rather than minting a parallel vocabulary |
| R541 (Spec) | consumer, and lands after this item. Its fork 1 and this item's fork 4 both resolve at R549: the launcher's `where` slot is the row's glue `UnitRef`, and row 5's naming lift is wholly this item's. Cross-referenced there |
| R333 (Ready) | owns row 5 and the target condition semantics; this item executes the row's "finish lift" verdict in command form and does not touch the `Single` value-gating semantic or the raw-relation re-sourcing |
| R475 (Backlog) | absorbed, by dissolution: the colliding generated parameter list stops existing when the entity layer retires (slice 3), and glue locals are producer-named collision-free. Tombstone at pickup, delete when this item reaches Done |
| R472 (closed 2026-07-29) | absorbed, in two steps under the decided ordering: slice 1 converted it to a deferred rejection, and R549 slice 3.1 supplied the nested coordinates that turned the rejection into an emitted body (the pinned fixture flipped from rejected to producing the nested row; the tombstone file is deleted per its own instruction) |
| R387 (Backlog) | absorbed: slice 1's per-arm glue renderer tests are R549 recipe step 5 applied to this family, and `TypeConditionsGeneratorTest` retires with its subject in slice 3 |
| R334 (Backlog) | **partially** absorbed: the glue body's one-local-per-argument convention is its fix for the condition family, and slice 2 removes the ternary chains from every condition call site. Its 2026-07-24 expanded scope reaches past this family, to the mutation insert-value ternaries, the polymorphic discriminator expression rebuilt per method, and the `$fields`-mapper deep-path extraction with its `inputs/*.fromMap` reuse finding; none of that is this item's, so R334 stays open and keeps those. Note the narrowing at pickup, do not delete the item |
| R11 (Backlog) | untouched: resolver-side, feeds new binding rows into the same relation when it lands |
| R245 (Backlog) | untouched: mutation conditions join the relation when their emit exists |

