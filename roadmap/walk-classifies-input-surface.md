---
id: R335
title: "Fold input/scalar/enum classification into the single classify-and-emit walk"
status: In Progress
bucket: architecture
priority: 4
theme: classification-model
depends-on: []
created: 2026-06-19
last-updated: 2026-07-24
---

# Fold input/scalar/enum classification into the single classify-and-emit walk

Offshoot of R317 (the single classify-and-emit walk, now Done). R317 made one
`SchemaTraverser.depthFirst` over the *output* surface the sole classifier of object /
interface / union types, with field classification folded onto the same enter visit. But the
walk's child function (`SchemaReachability.childrenOf`) descends output edges only
(field-output, union-member, interface-implementor, object/interface `implements`), so it never
reaches the *input* surface: input objects, and the scalars / enums that sit only on argument and
input-field coordinates. Those leaf kinds are still classified in a separate pre-walk sweep,
`TypeBuilder.prepareForWalk` looping `classifyAndRegister` over `getAllTypesAsList()`
(`TypeBuilder.java:231-239` at the time of writing). The walk is "single" for outputs and two-pass for everything else.

This item makes the walk classify the whole reachable surface by extending the traverser's child
function to the input edges, so inputs / scalars / enums are classified by the visitor as the walk
reaches them, and the pre-walk leaf sweep is deleted. The result is one traversal that classifies
every kind, output and input alike.

## Why this is reachable now (the enabling fact, as corrected in flight)

The pre-walk sweep classifies leaves *before* the walk for a stated reason that R317 itself made
stale: that "field classification reads input / scalar / enum verdicts from `ctx.types` during the
walk" (the `prepareForWalk` javadoc). The spec's original claim was that every such read now goes
through `TypeBuilder.lookAheadVerdict(...)` (registry-free recompute from SDL + reflection
bindings + catalog). **Implementation found that claim held lexically for `FieldBuilder` only**:
field classification transitively reached live `ctx.types` leaf reads in `EnumMappingResolver`
(enum-constant parity), `InputBeanResolver` (jOOQ-record / `@table` input-param arms and the
wire-coercion aggregate), `ServiceCatalog` (arg extraction, slot-type mapping, the arity-unique
gate's aggregate scalar predicate), and `TypeBuilder.resolveInputElementJavaType` (input record
shapes reading scalar verdicts). Under the extended walk those reads would have missed
deterministically for root-level `@service` fields, mostly in the permissive direction (lost
wire-coercion and enum-divergence rejections, a jOOQ-record param falling to the bean path).

The shipped resolution extends the read-free program instead of working around it: keyed
leaf reads route through `BuildContext.lookAheadVerdict` (delegating to the memoized
`TypeBuilder.lookAheadVerdict`, registry-view fallback only for unit-tier harnesses that wire no
`TypeBuilder`), and the scalar axis becomes a fixed point (`BuildContext.scalarVerdicts`,
populated by `buildClassificationIndices` beside the node / table / error indices) consumed by
both the aggregate predicates (`WireCoercionResolver.checkScalar`,
`ScalarTypeResolver.isClassifiedScalarJavaType`) and the keyed scalar reads. With that, the
read-free visitor invariant (R317 / R325) genuinely holds for the whole field-classification
surface, and order-independence on the input edges follows for the same reason it holds for the
output edges. R531 (`classify-time-registry-read-guard`) is filed to pin the now-multi-class
invariant with a lexical meta-test.

## Decisions settled (forks the author and reviewer have closed)

- **Pruning is in scope and is the point.** Today the leaf sweep runs over the unpruned, all-declared
  `getAllTypesAsList()`, so an input / scalar / enum that no field or argument reaches is still
  classified and lands in `GraphitronSchema.types()`. Moving classification onto the walk makes leaf
  classification reachability-driven: an unreached leaf is an orphan and is pruned, exactly as R279
  slice 6 made an unreached output object an orphan prune. This makes reachability uniform across all
  kinds (today: outputs pruned, leaves classified whether used or not), which is the simplification.
  This is a real model delta (a previously-classified unreachable leaf disappears from `types()`) and
  carries primary-gate coverage; see Acceptance.
- **Pruned-leaf warnings are out of scope.** Any "you declared an input / enum / scalar nothing
  reaches" diagnostic is a validation sweep over the schema, not a classification concern. R335 prunes
  silently (a pruned leaf is simply absent from `types()`), exactly as the output orphan prune does;
  surfacing it belongs with the existing `warn-on-pruned-unreachable-types` backlog item (scoped to
  output types today), which this item's prune widens the surface for. Cross-reference, do not absorb.

## The shape (shipped; deltas from the reviewed spec noted inline)

Three moves, plus the deletions they enable, all landed:

1. **Extend `SchemaReachability.childrenOf`** with the input edges. For a `GraphQLObjectType` /
   `GraphQLInterfaceType`, descend each field's *argument* types (unwrapped) in addition to its
   output target. Add a `GraphQLInputObjectType` arm descending each input field's type (unwrapped).
   Scalars and enums stay leaves (`default -> List.of()`). The existing `expanded` identity-set
   already terminates recursive input types and dedups shared scalars, so no new cycle handling is
   needed. `reachableTypeNames` shares `childrenOf`; widening it means the observatory now reports
   reachable leaves too. Leave the observatory's recorded *set* output-only (do **not** widen
   `recordIfNamedType`, `SchemaReachability.java:135-142`) and let the *walk* (the classifier) own the
   new edges, so `SchemaReachabilityTest`'s `reachable ⊆ classified` invariant is not silently
   restated. **Update the class-level javadoc** at `SchemaReachability.java:54-57` as part of this
   move: it currently states arguments and input objects are "deliberately not descended" and that
   "no output type is reachable only through an argument position", which becomes false the moment the
   edges are added. That prose is load-bearing (it explains *why* the child function was output-only);
   leaving it makes it a false invariant no test pins. **No default-value descent is needed**: an enum
   or scalar is reachable through an argument or input-field *type* edge, and by the SDL conformance
   rule a default-value literal must conform to its declared type, so the type edges already subsume
   every leaf a default value could reference; there is no separate "default literal references a leaf
   the type edge misses" case.
2. **Add visitor callbacks** to `GraphitronSchemaBuilder.ClassifyingVisitor`:
   `visitGraphQLInputObjectType`, `visitGraphQLScalarType`, `visitGraphQLEnumType`, each calling
   `typeBuilder.classifyAndRegister(node)`. `classifyType` already handles all three kinds; the only
   change is the call site moving from the sweep to the visit. The new leaf arms are safe under the
   `null`-fieldBuilder types-only seam: unlike the object arm (which guards field classification behind
   `fieldBuilder != null` in the `ClassifyingVisitor` object arm), the leaf arms only call
   `classifyAndRegister` and do no field work, so they need no `fieldBuilder` guard. The types-only test
   seam (`GraphitronSchemaBuilder.buildContextForTests`) drives the same
   `ClassifyingVisitor`, so under the extended edges it now descends the input surface and fires these
   leaf callbacks too, classifying leaves through the walk; its post-sweep expectations move with it.
3. **Delete the pre-walk leaf sweep** in `prepareForWalk` (the `getAllTypesAsList` loop calling
   `classifyAndRegister` on non-composite kinds). `prepareForWalk` keeps its other work; see below.

Implementation deltas beyond the three moves (all in service of the corrected enabling fact and
the guard shape below):

- `SchemaReachability.outputTargets` became `fieldTargets` (output target plus argument types);
  the class and method javadocs were rewritten as specced.
- `BuildContext.lookAheadVerdict(String)` (new): the one mid-walk verdict seam for helper classes,
  delegating to `TypeBuilder.lookAheadVerdict`; unit-tier fallback to the registry view.
- `BuildContext.scalarVerdicts` (new fixed point) populated by `buildClassificationIndices`;
  consumed by `ServiceCatalog.argExtraction` / `isClassifiedScalarJavaTypeName` /
  `mapToJavaTypeName`, `InputBeanResolver`'s wire-coercion call, and
  `TypeBuilder.resolveInputElementJavaType`.
- `BuildContext.locationOf(GraphQLNamedType)` gained the missing `GraphQLInputObjectType` arm, so
  the reconstructed rejection payload's location equals the seeded one for inputs.
- The corpus `pivot` example's vocabulary enum (`Sprak`) lost its `@classifiedType(as: EnumType)`
  annotation: `@pivot(vocabulary:)` references the enum by name only, never on a type coordinate,
  so it is now pruned; the pivot classifier reads the value mapping straight off the SDL and the
  `EnumType` verdict stays pinned by the corpus `enum-column` example.
- **Survivor directive definitions seed their argument types** (`SchemaReachability.seeds`, gated
  on `DeclaredDirectives.names()`, the same fact `SchemaDirectiveRegistry.isSurvivor` derives
  from). Found at the execution tier: the emitted schema re-declares every non-generator-only
  directive definition, so a scalar reachable only through such a definition's argument
  (`federation__FieldSet` on `@key`) must classify or `GraphitronSchema.build()` dangles a type
  reference. Graphitron's own build-time directives stay excluded, keeping the published-support-
  type gate (`SortDirection`) the sole owner of support-type retention. Pinned by the
  `SurvivorKind` case in `SchemaReachabilityTest` and the federation execution suites.

## What stays in `prepareForWalk` (and must be checked, not assumed)

These passes iterate `getAllTypesAsList` for reasons independent of the walk and do **not** move:

- `buildClassificationIndices` (node / table / error / participant) is deliberately a superset over
  all declared types (R317 slice 3d); untouched.
- `emitDirectiveIgnoredWarning` is an order-stable SDL-order pass reading only the reflection fixed
  point; untouched.
- `retainedSupportTypes()` carries the *same* all-declared dependency as the indices, less obviously:
  `classifyType`'s published-support-type arm (`SortDirection`) gates on `retainedSupportTypes()`
  (gate at `TypeBuilder.java:994-1002`, definition at `:1102-1117`, at the time of writing), which scans `getAllTypesAsList()` for references to the
  published support types. It is registry-free, so the read-free invariant survives, but it is an
  all-declared *superset* scan, not reachability-pruned. The interaction with R335: when
  `SortDirection` moves from the sweep onto the walk, its verdict still depends on this all-declared
  reference scan, while whether it lands in `types()` is now decided by the (reachability-pruned) walk.
  A `SortDirection` referenced only from an unreachable coordinate is still "retained" by the scan yet
  never visited by the walk, so it is pruned from `types()` anyway. That is the correct outcome under
  the settled prune fork, but it is exactly where a prune-vs-retain mismatch would hide; the
  prune-proof acceptance case must include a published-support-type sub-case (see Acceptance).
- `TypeBuilder.surfaceMultiProducerRejections` pre-registers `UnclassifiedType` demotions for binding-rejected
  types, inputs included (invoked from `prepareForWalk`; the `register` demote call sits at its tail). This is the load-bearing interaction,
  and it is **not** automatically idempotent under `register`. Under R335 a *reachable* rejected input
  is also visited by the walk, which calls `classifyAndRegister`. If `classifyAndRegister` runs
  `classifyType` first, it returns a live `TableInputType` / `InputType` verdict; the registry already
  holds an `UnclassifiedType`, the classes differ, so `TypeRegistry.register`'s final arm
  (`TypeRegistry.java:94-102`) **re-demotes to a fresh generic-structural `UnclassifiedType`**, clobbering
  the typed `Rejection` payload (`RecordBindingMultiProducer`) the validator and candidate-hint path
  key on. The fix is mandatory, not conditional. **Shipped shape (stronger than the specced
  mirror):** rather than mirroring the rejection-first check into a second body,
  `classifyAndRegister` now registers `lookAheadVerdict(name)` itself, and the rejection-first
  precedence has a single producer (`TypeBuilder.bindingRejectionVerdict`, also used by
  `participantClassification`), so the payload the walk re-registers is `equals`-identical to the
  seeded one by construction, and `register`'s `equals`-idempotent arm fires instead of the demote
  arm. `lookAheadVerdict` is memoized per name (the registry and the look-ahead are two
  materializations of one computation, and classification side effects such as the id-reference
  shim WARN fire once per type however many edges read it); the memo is cleared at the end of
  `prepareForWalk` because `resolveAll`'s DML grounding probes the payload scan mid-fold, and a
  verdict computed then predates the fixed point.

## Slicing

Per the anti-narrative rule (no slice may be structure-only), each slice changes observable behaviour
or is folded with one that does:

1. **Extend the child function + add the visitor callbacks + delete the sweep + the
   `bindings.rejection`-first guard in `classifyAndRegister`, in one slice.** This is the behaviour
   change (leaves become reachability-pruned) and cannot be split into a structure-only precursor:
   adding the edges without deleting the sweep does not merely double-classify wastefully, it
   *corrupts* the multi-producer rejection payload (the sweep registers the typed `UnclassifiedType`,
   then the walk's second `register` re-demotes to the generic structural one; see the
   `surfaceMultiProducerRejections` note above), so the two halves are payload-load-bearing on each
   other; deleting the sweep without the edges drops all leaves. The `bindings.rejection`-first guard
   is part of this slice, not a follow-on: the re-demote drift is deterministic for every reachable
   rejected input the moment the walk visits it, so there is no "land it whole, fix drift if it
   appears" branch; the guard ships with the edges. Gated by the primary-gate coverage below.
   Shipped as one slice as planned, with the guard in its single-producer form (see the
   `surfaceMultiProducerRejections` note above) and the read-free precursor re-routes folded in
   (they are payload-load-bearing the same way: deleting the sweep without them silently drops
   wire-coercion and enum-parity rejections on `@service` coordinates).

## Acceptance (delivered)

- Primary gate (`GraphitronSchemaBuilderTest` truth table + sakila pipeline `TypeSpec` + Java-17
  `graphitron-sakila-example` compile + PostgreSQL execution tier) stays green.
- The prune proof: `SchemaReachabilityTest.walkClassifiesLeavesAndPrunesUnreachedOnes` (reached
  `LeafProbeFilter` / `LeafProbeKind` present; `OrphanFilter` / `OrphanKind` / `OrphanStamp`
  absent), including the **published-support-type sub-case** (`SortDirection` referenced only from
  the unreachable `OrphanSortHolder`: retained by the all-declared `retainedSupportTypes()` scan,
  pruned by the walk).
- Order-independence on the input surface:
  `SingleWalkClassificationOrderTest.noInputTypeIsRegisteredBeforeItsDiscoveringFieldIsVisited`,
  mirroring the R317 output-surface test in the same class.
- The rejection-variant proof:
  `R96RecordBindingPipelineTest.multiProducerInput_reachableThroughTheWalk_keepsTypedRejection`
  (reachable multi-producer input stays `UnclassifiedType` with the `RecordBindingMultiProducer`
  payload, not a generic-structural re-demote).
- `SchemaReachabilityTest`'s `reachable ⊆ classified` invariant holds under the widened walk
  (recorded set stays output-only; the leaf prune is pinned by its own case, not by restating the
  invariant).
- Pre-existing coverage that now exercises the re-routed reads: the `@service` bean-enum parity
  and jOOQ-record param suites (`GraphitronSchemaBuilderTest`, `JooqRecordServiceParamPipelineTest`)
  and the wire-coercion cast-guard suite (`WireCoercionCastGuardPipelineTest`) all run against the
  extended walk with leaves visited after their consuming fields.
- Fixture migration: truth-table SDL fixtures that declared leaves nothing consumed gained
  reaching coordinates (`leafReach<i>(in: X)` args or args on existing fields), since a declared
  and consumed leaf is now the only classified leaf; `ServiceCatalogTest`'s consumer-scalar
  unit case seeds `BuildContext.scalarVerdicts` instead of the registry.

## Relationship to other items

- **R317** (Done): the parent. R335 extends R317's single-walk thesis from the output surface to the
  whole surface; it does not reopen any R317 mechanism.
- **R97** (`consumer-derived-input-tables`, Done 2026-07-24; absorbed R327's field-relative input
  classification on 2026-06-22): R97 shipped first, so R335 is the second shipper and was built
  against the consumer-derived call sites (`buildInputType` directive-driven only). R97 changed
  *how* an input's table-boundness is derived; R335 changes *where and when* an input is classified
  (on the walk, reachability-pruned, vs the pre-walk all-declared sweep).
- **R519** (`remove-table-on-input-directive`, Spec): will delete `TableInputType`; its rebase over
  this item is mechanical (the `InputBeanResolver` `@table`-conflict arm reads the verdict through
  `BuildContext.lookAheadVerdict` now).
- **R531** (`classify-time-registry-read-guard`, Backlog, filed by this item): the lexical
  meta-test pinning the now-multi-class read-free invariant.
- **warn-on-pruned-unreachable-types** (Backlog): R335 widens the pruned surface from output types to
  all kinds; that item's warning should grow to cover the leaves R335 starts pruning. Out of scope here.

## Retired vocabulary

- `EnumMappingResolver.buildTextEnumMapping` (dead method deleted; its unguarded registry read was
  inside the class whose read discipline this item extends)
- `SchemaReachability.outputTargets` (renamed to `fieldTargets` when the argument edges were added)
- the "pre-walk leaf sweep" / "input / scalar / enum kinds the output walk never reaches" framing
  (no kind is classified before the walk anymore)
