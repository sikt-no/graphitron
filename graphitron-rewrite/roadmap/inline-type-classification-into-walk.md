---
id: R317
title: "Single edge-driven classification pass and immutable validation (retire TypeBuilder.buildTypes)"
status: In Progress
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-06-16
last-updated: 2026-06-17
---

# Single edge-driven classification pass and immutable validation (retire TypeBuilder.buildTypes)

> **Status (resume pointer).** Slices 1, 2, 3a, 3b, 3c, 3d, 3e and 4 are shipped to trunk; slice 5
> (immutable validate) is the active front. Slice 4 collapsed the three traversals into one
> `SchemaTraverser.depthFirst` (a real `GraphQLTypeVisitor` classifying composites on enter and folding
> field classification into the same visit), deleted `buildTypes` / the `reachableOutputTypes` hand-off
> / the separate field loop, and is byte-identical across the truth table (448) and the full pipeline /
> compile / execution tiers, gated by the falsifiable acceptance test
> `SingleWalkClassificationOrderTest` (no type registered before its discovering field is visited). The
> collapse surfaced that the read-free precondition was incomplete after 3e: `BuildContext.resolveReturnType`
> (the central return-type resolver) plus `BuildContext` node / error / DML-element reads and the
> `ServiceCatalog.getTableSqlNameForType` / `SourceRowDirectiveResolver` table reads still read the
> in-progress registry; slice 4 migrated all of them to the registry-free look-ahead / fixed-point indices,
> so field classification is now read-free in fact, not just in `FieldBuilder`. Two deviations from the
> slice-4 plan as written: (a) enums / scalars are classified up front by the pre-walk sweep (moved ahead
> of the walk, where input / scalar / enum verdicts the helpers read must already exist) rather than via a
> dedicated `EnumIndex` / scalar index, which the pre-walk sweep makes unnecessary; (b) the field-relative
> input model (classify the input after its field's target, deriving table-boundness from the target) is
> deferred to its own item, R327, since it is the one non-byte-identical change and slice 4 stayed
> byte-identical.
> Slice
> 3e closed the last classification-edge registry reads: the interface / union / result-family target
> reads in `FieldBuilder` (`:672` / `:2120` / `:3236` / `:3291` / `:4654` / `:5046`) now resolve
> through a registry-free look-ahead (`TypeBuilder.lookAheadVerdict`, a pure `classifyType` + carrier
> fixed point), so field classification no longer depends on the target type having been registered.
> This was forced by an investigation finding for slice 4: `SchemaTraverser.depthFirst` fires the
> `GraphQLTypeVisitor` on **enter only** (`TraverserDelegateVisitor.leave` is a hard-coded `CONTINUE`,
> confirmed by decompiling graphql-java 25), so the spec's original "each field in post-order, its
> edge-local target already classified" premise is unavailable; a field's output target is a
> not-yet-visited child. Look-ahead (rather than a new InterfaceType / UnionType index, or a different
> traverser) closes that gap by reading the target's verdict from SDL + bindings on demand. The four
> `ctx.types` reads that remain in `FieldBuilder` are not enter-unsafe target reads: inputs (`:963` /
> `:3708`) and scalar (`:4645`) are served by the pre-walk input/scalar/enum sweep, and `:5436` is an
> error-message candidate-name hint (slice 4 must source it off the schema, not the partial registry).
> Slice 3d lifted
> table / node / error membership to three pure, typename-keyed fixed-point indices (`ctx.tables`,
> `ctx.nodes`, `ctx.errors`), directive-scanned over all types as a superset and driven off the pure
> `classifyType` verdict; the deep `FieldBuilder` reads (union-member `ErrorType`, payload-data-field
> `TableBackedType`) and the table/node reads now go through the indices, so field classification is
> registry-free for those facts. The indices carry no classification duty: the typeId-uniqueness
> exclusion came out of `NodeIndex` (it conflated indexing with validation in slice 2), leaving
> `validateNodeTypeIdUniqueness` the sole owner of uniqueness; classification now succeeds against a
> typeId-collided node (the field resolves via the index), and the validation pass flags the
> collision so the invalid schema does not generate. Slice 3c made the
> last orphan verdict at a classification edge registry-free: the `@service`-mutation orphan-carrier
> rejection (`FieldBuilder` Scalar arm) now reads `TypeBuilder.carrierTableBinding` instead of
> `ctx.types.get(payload)`, so the edge owns the edge-decidable orphan without a read into the
> in-progress registry; the whole-registry dangling-reference backstop stays for rescuable shapes.
> Slice 2's
> `NodeIndex` (`ctx.nodes`), as of slice 3d, is a pure typename-keyed index: SDL-derived (off the
> `classifyType` verdict) over **all** types as a superset, with **no** reachability prune and **no**
> typeId-collision exclusion, and is **one-to-many by table** (a table may back several
> `@node` types with distinct node ids; ambiguity of the implicit encoder is rejected at the call
> site, not by a type-level guard). All `classifyField` node reads go through `ctx.nodes` (`forTable`
> / `forName`), including the `@nodeId(typeName:)` path. Slice 3a folded `registerNestingTypes` onto
> the embedding edge via a registry-free nesting verdict (`TypeBuilder.isDirectivelessNestingTarget`,
> `carrierTableBinding`); 3b folded `promoteSingleRecordPayloads` the same way, landing the
> `JooqTableRecordType` verdict at the producing edge (the field returning the carrier) from
> `carrierTableBinding`, before that field classifies, so its `resolveReturnType` routes through the
> record-backed mutation path without reading a not-yet-registered carrier. See the slice list and
> "Design decisions".
>
> **R325 folded in (2026-06-17).** R325 ("Classify in a single field-first visitor walk") was a
> from-first-principles restatement of this item's goal. It is folded in here and discarded as a
> separate item; its substance lives in "Read-free visitor invariant and the single walk" below. The
> load-bearing additions it contributes: the **read-free visitor invariant** (the classifying visit
> may only `register`, never read the registry under construction); parent context flows down the
> `SchemaTraverser` var channel, not via registry reads; a **falsifiable acceptance test** (no type
> is registered before its discovering field is visited) that today's eager type pass fails; and an
> **anti-narrative slicing rule** (no remaining slice may be structure-only / "byte-identical" in the
> R279 sense; each must delete a pass or move classification into a visit callback). The one
> mechanism fork it raised, no global pre-pass fixed points vs. this item's precomputed indices, is
> settled: a precomputed fixed point passed as an explicit traverser argument honors the read-free
> invariant (it is not the registry under construction and not ambient global state), so the
> `NodeIndex` stays precomputed and is threaded in as a traverser argument; reflection grounding
> becomes on-demand at the visit (no `RecordBindingResolver.resolveAll()` precondition), per R325.
>
> Rescoped 2026-06-17, mid-implementation. The original plan framed this as a byte-identical
> reordering that moved the existing passes into the walk. Investigation (see "Why the original
> framing could not reach the goal") showed that framing cannot land the single walk: the walk
> requires `classifyType` to become edge-complete and the reverse-index registry scans to become
> fixed points, and a genuinely simple end state also requires the validate phase to stop mutating
> classification. R318 (immutable validate phase) is inlined here as the closing slice; R319
> (warn on pruned unreachable types) stays separate.

## Problem

R279 made classification field-first and reachability-pruned and collapsed `TypeRegistry` to one
`register` verb, but the reachable surface is still traversed three times: `SchemaReachability`
computes a `Set<String>`; `TypeBuilder.buildTypes` loops it classifying each type and then runs its
deferred passes; `GraphitronSchemaBuilder.buildSchema` loops it again classifying fields. The set is
threaded across two classes as a hand-off (`TypeBuilder.reachableOutputTypes`). The goal is one walk
that classifies as it goes, with `buildTypes` deleted.

Two structural facts block that, and a third keeps the validate phase from being clean:

1. **`classifyType` is a standalone, own-directive classifier that punts on directiveless objects.**
   For a directiveless object it returns `null` (TypeBuilder.classifyType), an open admission that
   it cannot decide the type standalone, because what such an object *is* depends on the edge that
   reaches it. The real verdict is then scattered across three later passes keyed on edge context
   discovered after `classifyType` ran: `promoteSingleRecordPayloads` (a directiveless carrier
   becomes `JooqTableRecordType`), `registerNestingTypes` (a directiveless object embedded by a
   `NestingField` becomes `NestingType`), and the orphan arm of `rejectDanglingTypeReferences` (a
   reached-but-neither object demotes its referencing field). That scatter is the inversion the
   field-first model set out to kill: fields should drive types, at the edge.

2. **`classifyField`'s only whole-registry reads are reverse-index queries over a fixed point.**
   Almost every `ctx.types.get(...)` in `FieldBuilder` is edge-local (the field's own
   return / element / member type, satisfiable by a post-order walk). The exceptions are two reverse
   indexes: `table -> NodeType` (four byte-identical `ctx.types.values()` scans resolving an
   ID-returning field's encode helper, `FieldBuilder` lines 3038 / 3496 / 3563 / 3770) and
   `participant-field -> crossTableField` (`lookupParticipantCrossTableField`). Both query
   information that is a registry-free fixed point
   (the `@node` / `@key` and `@reference` SDL plus the catalog and reflection bindings, the same
   inputs the classifier itself consumes). Five consumers evaluating one predicate is the
   under-specified-model smell; the fix is to compute the index once as a fixed point.

3. **The validate phase mutates classification.** The global soundness reductions
   (`validateNodeTypeIdUniqueness`, `rejectCaseInsensitiveTypeCollisions`,
   `collectDomainReturnTypeConflicts`, the dangling-reference backstop, and the carrier-shape scan)
   surface their findings by demoting to `UnclassifiedType` / `UnclassifiedField`. So "what a type
   is" and "what is wrong with the schema" are entangled in one mutable registry value, and a
   verdict read after validation is not the verdict classification produced.

## Why the original framing could not reach the goal

A byte-identical reorder leaves `classifyType` punting and the reverse scans reading the live
registry, so field classification still needs the complete type map first and cannot fold into the
walk. The single walk is not a reordering; it is the model correction in (1) and (2). And a clean
classify-then-validate boundary is not reachable while (3) holds. This item does that work.

## Goal

One edge-driven classification pass, then an immutable validate pass, then emit.

- **One classification pass.** The `SchemaTraverser` walk classifies each type as the edge reaches
  it (directiveless objects classified at the reaching edge from the parent's nature, carried down
  the walk, plus the binding fixed points) and each field in post-order (its edge-local target is
  already classified). The two reverse-lookups are served by precomputed fixed-point indices, so
  `classifyField` has no whole-registry dependency. `buildTypes` is deleted; the
  `reachableOutputTypes` cross-class hand-off is retired.
- **Immutable validate pass.** The global reductions read the finished registry and *register
  diagnostics*; they do not reclassify. After validation, a verdict equals the verdict
  classification produced.
- Verdicts stay order-independent: every verdict reads only node SDL, reflection, the fixed-point
  indices, and downward walk context, never the mutable in-progress registry sideways or back.

## Design decisions (settled with the principles-architect pass, 2026-06-17)

- **Fixed-point indices derive from the fixed point, not from the registry, and are passed as
  explicit traverser arguments.** `table -> NodeType` is built from the `@node` / `@key` SDL scan plus
  the catalog (the inputs `NodeType` itself comes from); `participant-field -> crossTableField` from
  the `@reference` SDL. Building either by scanning `ctx.types.values()` and memoising would create a
  second producer of the same fact that can drift; deriving from the fixed point keeps one producer.
  The encode-helper the `table` index yields is itself derivable (encoder class constant + `"encode" +
  typeName` + keyColumns), so the index need not hold a `NodeType` registry entry. **Reconciled with
  R325's read-free invariant:** a precomputed fixed point is not the registry under construction and
  not ambient global state, so the visit *reading* it does not violate the invariant, *provided it is
  threaded in as a traverser argument* rather than reached through a mutable global. The `NodeIndex`
  is trivial to precompute and is passed as such an argument. Reflection grounding, by contrast,
  becomes on-demand at the visit that needs it (no `RecordBindingResolver.resolveAll()` precondition),
  per R325; the read-free invariant is the governing constraint, not "no precomputation."
- **`table -> NodeType` is one-to-many; ambiguity is a use-site concern, not a type-level guard.**
  A table can legitimately back several `@node` types (distinct node ids over the same rows; e.g.
  sis `Soker` / `Studiekurv` on table `soker`), so the by-table index lists *every* node on a table
  rather than collapsing to one. (An earlier revision of this decision wrongly proposed a global
  "one `@node` per table" validator mirror; it rejected valid schemas and was reverted.) The old
  `findFirst()` silently picked one node for the implicit "encoder for this table" form, which was a
  latent bug: where the table is ambiguous, the implicit form (a bare-`ID` mutation return, or an
  `@nodeId`-less carrier) has no single answer. Resolution moves to the call site: one node resolves,
  zero is the existing "no `@node` for table" error, and two-or-more is a new use-site rejection with
  a disambiguation hint (`@nodeId(typeName:)` on a carrier, or a typed `@node` return for a direct
  bare-`ID` mutation). This fires only when the ambiguous implicit path is exercised, so a schema
  with several nodes on one table that disambiguates via explicit `@nodeId` (the by-name view) builds
  unchanged.
- **Carrier binding takes the fixed-point route, not post-order (shipped, slice 3b).** The
  `JooqTableRecordType` verdict needs no descent: the table is known from the producer binding
  (`dmlEmittedBinding` / `serviceEmittedBinding`), exposed registry-free as
  `TypeBuilder.carrierTableBinding`. The verdict is landed at the **producing edge**: the field
  whose return type is the carrier registers the `JooqTableRecordType` (from `carrierTableBinding`)
  *before* it classifies itself (`GraphitronSchemaBuilder.registerProducerBackedCarrier`, called per
  field in `classifyFieldsOfObject`). That ordering is what retires the classify-reads-classify
  dependency in practice: the producing field's `resolveReturnType` reads the carrier as a
  `ResultType` and routes through the record-backed mutation path, and the carrier's own later visit
  reads the verdict as its `parentType`, with neither reading a not-yet-registered carrier. The
  former `promoteSingleRecordPayloads` post-type-pass SDL scan is deleted. Only the structural
  carrier scan still reads element verdicts (it runs after `buildTypes`, when the element registry is
  complete); that scan is a validation guard in classification costume, and re-expressing it as a
  validate-phase diagnostic is deferred to slice 5, not relocated here.
- **Nesting becomes a product of the embedding edge (shipped, slice 3a).** `NestingField`
  construction needs only the parent's table context plus a negative verdict on the target ("the
  target carries no competing classification"). The former guard read that verdict from the registry
  (`elementType == null || elementType instanceof NestingType`); once registration folds onto the
  edge, that read would observe a *sibling* edge's `NestingType`, breaking the read-free invariant
  (R325). So the guard is not narrowed to `== null`; it is replaced by a registry-free structural
  verdict, `TypeBuilder.isDirectivelessNestingTarget` (`classifyType(X) == null`, minus multi-producer
  rejections and producer-bound carriers via `carrierTableBinding`). Two parents embedding the same
  directiveless object each classify it independently and identically, neither reading the other; the
  `NestingType` registration is a write-only side-effect no edge consumes. `registerNestingTypes` (the
  post-walk sweep) is deleted.
- **Orphan handling stays split (shipped, slice 3c).** The edge produces `UnclassifiedField` directly
  for an edge-decidable orphan (no reclassify, which is already the immutable-validate end shape for
  free). The one such verdict that still read the in-progress registry was the `@service`-mutation
  orphan-carrier rejection: a carrier-shaped payload reaching the resolver's `Scalar` arm with no
  producer binding. Its `ctx.types.get(payload) == null` gate is replaced by
  `TypeBuilder.carrierTableBinding(payload) == null`, the same registry-free scan + producer-binding
  fixed point `registerProducerBackedCarrier` registers from; a carrier scan `Admit` with a null
  `carrierTableBinding` is definitionally an orphan (a bound producer would have registered a
  `JooqTableRecordType` at the producing edge and the resolver would have yielded `Result`, not
  `Scalar`), so the verdict is order-independent. The whole-registry dangling-reference *backstop*
  stays a validate-phase reduction: a non-carrier target that looks orphaned mid-walk may be rescued
  by a later nesting or connection edge, so a per-edge final demotion is not sound. Under the
  immutable validate phase the backstop registers a diagnostic rather than demoting.

## Read-free visitor invariant and the single walk (folded from R325, 2026-06-17)

The terminal mechanism (slice 4) is a single `SchemaTraverser.depthFirst` call with a **real**
`GraphQLTypeVisitor`, not the current no-op `GraphQLTypeVisitorStub` that `SchemaReachability` runs
only to collect a name set. `TypeBuilder.buildTypes()` as a standalone type pass and the separate
`classifyFieldsOfObject` loop in `GraphitronSchemaBuilder.buildSchema` are **deleted**, not wrapped.
Visiting a `Query` / `Mutation` field classifies the field and its target type in the same visit; on
arriving at a non-root type its verdict already exists (the field that reached it set it), so its own
fields classify in turn.

**The invariant: the visit never reads the registry it is building.** The classifying visit may only
`register`, never read back, the model under construction. A child's classification is a pure
function of its own node and directives, the catalog, the precomputed fixed points threaded in as
traverser arguments (the `NodeIndex`), and state handed down from its parent through the traverser's
var channel, never a lookup of a sibling's or parent's verdict from the registry. With no read path
into the in-progress registry, order-dependence bugs become unrepresentable and the registry is
write-only during the walk. This is the structural form of the "fields drive types" goal, and it is
what slice 3a already enforced locally for the nesting verdict. Two boundaries, both decided:

- **Reconciliation lives in the registry; the visit stays pure.** A type reached by multiple
  producers is registered more than once; `TypeRegistry.register` (the single reconciling write verb
  R279 landed) collects and reconciles those writes (equal -> idempotent, compatible -> merge,
  incompatible -> demote). The visit never reads back what it registered. Multi-producer agreement is
  therefore not the visit's concern.
- **Consuming the parent-var is per-node-kind, not universal.** An interface reached via an object's
  `implements` clause must not classify as a function of that object; it classifies from its own
  directives and participants. The rule is "may not read the registry," not "must read the
  parent-var"; some node kinds ignore the channel.

**Parent context flows via the var channel.** A parent visit `setVar(Key.class, value)`; the child
reads `getVarFromParents(Key.class)` (graphql-java's `TraverserContext`, already used via
`getParentNode()` in `ConnectionPromoter`). The exact keys (parent verdict, parent `TableRef`,
source-shape context) are enumerated when slice 4 lands.

**Why dedup is not a problem (decided).** `SchemaTraverser` descends a node reached by two parents
once: verified in `graphql.util.Traverser`, the per-node dispatch checks `isVisited()` and routes a
re-encounter to `visitor.backRef` (phase `BACKREF`), calling `visitor.enter` exactly once per node
identity and only then `addVisited`. So a real `GraphQLTypeVisitor`'s `enter` (the
`visitGraphQL*Type` callbacks) fires once per type, no dedup needed on our side. This once-per-node
property is what makes on-enter classification viable, and it is irrelevant to multi-parent
reconciliation anyway, because every type's registry entry is *parent-independent* and all
parent-relative context lives on the per-site field. A directiveless projection registers a bare
`NestingType` *marker* that carries **no table** (`(name, location, schemaType)`); registering it
once per incoming field is N identical writes the registry collapses trivially. The projection's
fields live on each embedding `NestingField` (`nf.nestedFields()`), classified in that parent's table
context, never in the global field registry under the shared type name, so they never collide and are
free to diverge per site. **The guard the implementation must hold:** a shared type's registry entry
stays parent-independent; the moment a `TableRef` or source shape lands on it, the N-identical-writes
property breaks. Parent context lives on the field.

**What classifies on the visit, and what is indexed up front (decided).** The once-per-node `enter`
makes the visitor the natural classifier for the **composite output types** (object / interface /
union): each is reached only through output edges (field return, union member, interface implementor,
object `implements`), so "reached by the walk" and "needs classifying" coincide, and a composite the
walk never reaches is an orphan we deliberately prune (the type-side of R279 slice 6). For those,
classify on `enter`. **Enums and scalars are different and are indexed up front instead** (an
`EnumIndex` / scalar index over *all* declared types, like the `@table` / `@node` / `@error`
indices). Their verdict is a global fact (a pure function of the type's own SDL), but the decisive
reason is reachability: an enum or scalar can appear *only* on an argument or input-field coordinate,
and the walk deliberately does not descend arguments or input objects, so its `enter` would never
fire for an argument-only enum (e.g. a `SortDirection` / filter enum) or input-only scalar. Those
still need a model entry (an enum emits a Java enum class, a scalar needs its resolution recorded)
regardless of coordinate, so on-visit classification would silently drop them. The up-front index
over all declared enums / scalars is reachability-independent and therefore complete, exactly as the
retired sweep was. (Reading a scalar verdict *at a field edge* — the slice-3e assignability
look-ahead — is a separate concern from *registering* every scalar into the model; the edge read
stays look-ahead, model-registration comes from the index.)

**Edges that are not literally "field -> return type"** must be carried explicitly or types dangle:
`@node` / `@key` seeds and the `Node` interface no field returns (already in
`SchemaReachability.seeds`); interface -> implementors and object -> implemented interfaces
(structural edges already in the custom child function); and arguments -> input types -> nested input
fields, resolved **per field-usage during field classification**, not via a separate visitor descent
and not reusing a sibling field's classification of the same input type. Whether a shared input type
needs a global (parent-independent) registry entry at all, or is purely field-level, is the one
input-specific question slice 4's implementation must answer.

**Anti-narrative slicing rule (folded from R325).** R279 sliced every step "byte-identical," which
guarantees the sum is narrative-only (if every step preserves behaviour and structure, only comments
moved). The remaining slices here are bound by a stricter rule: **the verdict for every existing
fixture stays unchanged** (output byte-identical, the non-negotiable correctness gate), but **no
remaining slice may be structure-only**, each must delete a pass or move classification logic into a
visit callback, with a behaviour-or-structure delta to show for it. A slice whose diff is only
javadoc is out of scope by construction. The collapse (slice 4) carries a **falsifiable acceptance
test**: assert that no type is registered before its discovering field is visited, a structural
assertion that cannot pass against the current eager type pass. If that test would pass unchanged
today, the slice did nothing.

## What stays after the walk (validate phase, now immutable)

`validateNodeTypeIdUniqueness`,
`rejectCaseInsensitiveTypeCollisions`, the dangling-reference backstop,
`collectDomainReturnTypeConflicts`, the carrier-shape scan, `EntityResolutionBuilder`, and
`MappingsConstantNameDedup`. These are reductions over the finished registry; a per-edge visit
cannot detect a cross-type conflict. They keep their place but, after the closing slice, register
diagnostics instead of reclassifying.

## Out of scope

R319 (emitting the warn-on-prune warning for unreachable types) stays a separate item; this item
only shifts unreachable types from silently classified to pruned. Migrating further reductions into
the LSP layer, and the broader diagnostic-surface redesign beyond what the inlined R318 needs, are
out of scope.

## Slicing

Each slice is independently gateable on the `GraphitronSchemaBuilderTest` truth table plus the
sakila pipeline / compile / execution tiers. The gate is two-part, reconciled with R325's
anti-narrative rule: **(1) output byte-identity for every existing fixture is the non-negotiable
correctness gate** (the verdict a fixture classifies to does not change, only where and when it is
produced), except where the old behaviour was a latent bug rather than a contract (slice 2 found that
`types.values().findFirst()` silently picked one `@node` when several backed a table, and corrected it
to a use-site ambiguity error instead of preserving the arbitrary pick); **(2) no remaining slice may
be structure-only / "byte-identical" in the R279 sense**, each deletes a pass or moves classification
logic into a visit callback. A slice whose only diff is javadoc is out of scope by construction. The
collapse (slice 4) additionally carries the falsifiable acceptance test from the R325 fold (no type
registered before its discovering field is visited). The slice-2 ambiguity rule and the slice-3c
orphan move are pinned by explicit tests, not claimed trivially.

1. **Participant enrichment onto the node visit.** *Shipped (`96b2f18`).* `classifyType` classifies
   each interface / union with its participants at the node visit; the separate enrichment pass and
   its helpers are deleted. Byte-identical.
2. **Lift the reverse-lookups to fixed-point indices.** *Shipped.* `BuildContext.nodes` (a
   `NodeIndex`: by-table one-to-many + by-name) and `BuildContext.crossTableFieldsByParticipant`
   (`participant -> field -> crossTableField`) are built once in `TypeBuilder.buildClassificationIndices`
   from the reachable `@node` / `@table`+`@discriminate` SDL scan via the same producers
   (`buildTableType` / `buildTableInterfaceType`), not memoised from the registry. The four
   encode-helper `types.values()` scans, `lookupParticipantCrossTableField`, *and* the keyed
   `ctx.types.get(explicitTypeName)` at the `@nodeId(typeName:)` path now read those maps
   (`forTable` / `forName` / the participant map), so `classifyField` has no NodeType or participant
   read into the type registry left at all. The index excludes only typeId-collision groups (which
   `validateNodeTypeIdUniqueness` demotes); a table backing several `@node` types is kept whole
   (`forTable` returns the list). The implicit "encoder for this table" form is resolved at the call
   site: one node resolves; zero is the existing "no `@node` for table" error; two-or-more is a new
   `IdEncoderResolution.Ambiguous` / `ambiguousImplicitNodeError` rejection with a disambiguation
   hint. Byte-identical across the pipeline / compile / execution tiers for single-node tables
   (the universal case); the new behavior is pinned by
   `NodeIdPipelineTest.TypeCase.MULTIPLE_NODE_TYPES_PER_TABLE_ALLOWED` (two `@node` types on one
   table both classify) and `MutationDmlNodeIdClassificationTest.idReturnOnMultiNodeTable_ambiguous_rejected`
   (the bare-`ID` implicit return on such a table is rejected with the disambiguation hint).
3a. **Fold nesting onto the embedding edge.** *Shipped (`5fb54de`).* The `NestingType` verdict is
   produced at the field-classification edge that builds the `NestingField`, decided by the
   registry-free `TypeBuilder.isDirectivelessNestingTarget` (`classifyType(X) == null` minus
   multi-producer rejections and producer-bound carriers via the extracted `carrierTableBinding`); the
   `FieldBuilder` guard and the `classifyFieldsOfObject` standalone-skip no longer read `ctx.types`,
   so no edge observes a sibling edge's `NestingType` (the read-free invariant, locally). The post-walk
   `registerNestingTypes` sweep is deleted; `registerNestingTypesIn` is called per classified field at
   the edge. Output byte-identical (truth table 447, `NestingFieldPipelineTest` incl. the
   `sharedNestedType` multi-parent case, node-id / mutation-DML / split-table); structure delta:
   a pass deleted, the verdict made registry-free.
3b. **Fold carrier promotion onto the producing edge.** *Shipped.* The `JooqTableRecordType` verdict
   is landed at the producing edge (the field returning the carrier) from the binding fixed-point
   (`TypeBuilder.carrierTableBinding`, exposed package-private since 3a), via
   `GraphitronSchemaBuilder.registerProducerBackedCarrier` called per field in `classifyFieldsOfObject`
   *before* that field classifies, so its `resolveReturnType` routes through the record-backed mutation
   path; the carrier's own later visit reads the verdict as `parentType`. The `promoteSingleRecordPayloads`
   post-type-pass SDL scan is deleted. The structural carrier scan stays a classification-time guard
   (re-expressing it as a validate-phase diagnostic is slice 5 territory), so the classify-reads-classify
   dependency it carries is not relocated here. Output byte-identical (truth table 447, full graphitron
   suite, sakila pipeline / compile / execution tiers); structure delta: a pass deleted, the carrier
   verdict moved onto the edge.
3c. **Move the edge-decidable orphan verdict to the edge.** *Shipped.* The edge-decidable orphan
   verdict is now produced read-free: the `@service`-mutation orphan-carrier rejection
   (`FieldBuilder` Scalar arm) gates on `TypeBuilder.carrierTableBinding(payload) == null` instead of
   `ctx.types.get(payload) == null`, the last orphan-related read into the in-progress registry at a
   classification edge, removed the same way slice 3a removed the nesting read and 3b the carrier read.
   The whole-registry dangling-reference *backstop* stays a post-walk reduction for rescuable
   (non-carrier) shapes: a target that looks orphaned mid-walk may be rescued by a later nesting or
   connection edge, so a per-edge final demotion is not sound. Output byte-identical (truth table now
   448 with the new pin, full graphitron suite, sakila pipeline / compile / execution tiers, 2976
   tests); structure delta: the edge orphan verdict made registry-free. Pinned by
   `GraphitronSchemaBuilderTest.UnclassifiedFieldCase.SERVICE_MUTATION_ID_CARRIER_UNBOUND_ORPHAN_REJECTED_AT_EDGE`
   (an `[ID] @nodeId` carrier whose producer return does not ground the binding is rejected at the
   producing edge with the ID-element guidance, never reaching the backstop's "not found in schema").
3d. **Lift table / node / error membership to pure fixed-point indices.** *Shipped.* The remaining
   `ctx.types.get(x) instanceof {TableBackedType | NodeType | ErrorType}` reads in `FieldBuilder` are
   the last registry reads at classification edges, and the *deep* ones (the union-member `ErrorType`
   read at `:1840`; the payload-data-field `TableBackedType` reads at `:2817` / `:2972`, which read a
   type **two hops below** the field being classified, e.g. `registerCustomer → CustomerPayload →
   customer → Customer`) are the structural blocker to the collapse: a single enter-only walk cannot
   have that deep type classified before the shallower field reads it. Retire them by generalising
   slice 2's `NodeIndex` into three **pure, typename-keyed** fixed-point indices, `TableIndex`
   (name → `TableRef`), `NodeIndex`, `ErrorIndex` (name → `ErrorType`), directive-scanned over all
   types (`@table` / `@node` / `@error`), a **superset of the reachable set, unpruned**. The superset
   needs no reachability pruning because every type a read actually queries is already reachable
   (`@node` / `@key` self-seed; a `@table` data field or `@error` member is queried only by a field
   that reaches it), so the index and the pruned registry agree on the consulted domain. The indices
   carry **no classification duty**: no demotion, no reachability prune, and **no typeId-uniqueness**
   exclusion (slice 2 conflated that into `NodeIndex`; it comes out here, with
   `validateNodeTypeIdUniqueness` left the sole owner of uniqueness as a validation reduction over the
   registry, eventually a slice-5 diagnostic). The `TableBackedType` / `ErrorType` / node reads at
   `:666`, `:1840`, `:2817`, `:2972`, `:3268`, `:5013`, `:5379` migrate to index lookups, so field
   classification becomes a function of its node + directives + these fixed points + the parent var,
   with no in-progress-registry read, the read-free invariant in full, and the deep/direct distinction
   dissolved. Output byte-identical over passing fixtures (uniqueness still fails the build via the
   validation pass before generation, so a collided node the field pass can now resolve never reaches
   output); if a typeId-collision fixture's exact error set shifts from a field-resolution rejection to
   the validation error, that is the intentional consequence of making validation the single owner and
   is pinned. Structure delta: the last classification-edge registry reads removed; `NodeIndex` reduced
   to a pure index. *As shipped:* the three indices are driven off the pure `classifyType` verdict
   (not the bare `buildTable*` / `buildErrorType` producers), so a directive conflict / catalog-miss /
   federation / support-type gate yields the same `UnclassifiedType` the registry would hold and the
   index agrees with the registry by construction. The six reads (`:666`, `:1840`, `:2817`, `:2972`,
   `:3268`, `:5379`) migrated; the `:666` / `:3268` table arm builds its `TableBoundReturnType` from
   the index verdict's `tbt.table()` directly (byte-identical to the former `resolveReturnType` cast,
   which would have thrown `ClassCastException` on a collided node now visible to the field pass). No
   test assertions changed; truth table 448, full pipeline / compile / execution build green. The
   typeId-collision case (`NodeIdPipelineTest.TYPE_ID_COLLISION_DEMOTES_BOTH`) confirms the model:
   classification succeeds against the collided node, `validateNodeTypeIdUniqueness` still flags it,
   and the invalid schema does not generate.
3e. **Look-ahead retires the last classification-edge registry reads.** *Shipped.* Slice 3d left the
   interface / union / result-family target reads in `FieldBuilder` reading `ctx.types`, on the
   premise (from the slice-4 spec) that they were "post-order satisfiable" once the walk classified a
   field's target before the field. Investigation for slice 4 falsified that premise:
   `SchemaTraverser.depthFirst` fires the `GraphQLTypeVisitor` on **enter only**
   (`TraverserDelegateVisitor.leave` is a hard-coded `return CONTINUE`, confirmed by decompiling
   graphql-java 25), so in the single walk a field's output target is a not-yet-visited child and the
   read would see no entry. Of the two strategies considered (look-ahead vs. a different traverser),
   look-ahead is taken: a registry-free `TypeBuilder.lookAheadVerdict(typeName)` returns the verdict
   the target resolves to from SDL + reflection bindings + catalog (`classifyType`) plus the
   producer-bound single-record carrier fixed point (`carrierTableBinding`), the one verdict
   `classifyType` leaves `null` that the registry holds non-null. This reproduces the value
   `ctx.types.get(name)` returned in the two-pass world (where `buildTypes` populated the registry
   before the field pass) byte-for-byte: a nesting target / orphan is `null` under both (its branch is
   decided separately by `isDirectivelessNestingTarget`), and the post-walk demotions (typeId
   uniqueness, multi-producer, case-fold) do not change any arm these reads test (a demoted node is
   read through the `NodeIndex`; a multi-producer / case-fold demotion turns a verdict already not
   interface / union / result into an `UnclassifiedType` still not one of those). The interface /
   union / result-family target reads migrated to `lookAheadVerdict`, and so did the argument
   **input** reads and the **scalar** assignability read: a field's input and scalar targets are
   reachable from the field by definition, and their interpretation belongs at the field's edge, not a
   global registry lookup, so they resolve through the same look-ahead rather than leaning on the
   pre-walk sweep. The input case is *why* look-ahead is the right mechanism here and not an index:
   whether an input is table-bound is a function of the field's target, so it cannot be a single global
   index entry (an index can't be field-relative; a look-ahead can take the field's target). To stay
   byte-identical, `lookAheadVerdict` reproduces the current global verdict (`buildInputType`'s
   `@table`-on-input plus the `findReturnTablesForInput` aggregate); the field-relative input model
   (classify the input *after* the target, deriving its table from the field's target, with
   `@table`-on-input de-emphasised) is a slice-4 change, gated there because moving off the global
   aggregate changes classification for an input used across more than one table. `lookAheadVerdict`
   runs the multi-producer rejection guard first (mirroring `participantClassification`) so a
   binding-rejected input reads as the `UnclassifiedType` `surfaceMultiProducerRejections` produced,
   not a live verdict. After this, the only `ctx.types` read left in `FieldBuilder` is the
   error-message candidate-name hint (`ctx.types.keySet()`); slice 4 sources it off the schema, not the
   partial registry. Output byte-identical (truth table 448, full pipeline / compile / execution build
   green); structure delta: every classification-edge target-verdict registry read removed, the
   read-free invariant holding for every target verdict.
4. **Collapse to one walk; delete `buildTypes`.** *Shipped.* The no-op `GraphQLTypeVisitorStub` in
   `SchemaReachability` is replaced by a real `GraphQLTypeVisitor` (`GraphitronSchemaBuilder.ClassifyingVisitor`,
   driven by the new `SchemaReachability.walk(schema, visitor)`) that classifies each composite type on
   enter (`TypeBuilder.classifyAndRegister`) and, for object types, classifies that object's fields in the
   same visit (`classifyFieldsOfObject`). This one `SchemaTraverser.depthFirst` replaces the three former
   traversals of the reachable surface: the `SchemaReachability` name-set walk, `buildTypes`' eager type
   loop, and `buildSchema`'s separate field loop. `buildTypes`, the `reachableOutputTypes` field +
   hand-off, and the field loop are deleted; `buildTypes` splits into `prepareForWalk` (bindings +
   indices + the pre-walk leaf sweep, below) and `finishTypeClassification` (the one post-walk reduction,
   `validateNodeTypeIdUniqueness`). The `buildContextForTests` seam runs the same walk with a null
   `FieldBuilder` (types only, no field side effects, so `NodeIdLeafResolverTest` still gets an
   unconsumed resolver).

   **The read-free precondition was incomplete after 3e, and slice 4 finished it.** Slice 3e migrated
   `FieldBuilder`'s own reads, but the collapse surfaced that field classification still read the
   in-progress registry through `BuildContext.resolveReturnType` (the central return-type resolver, called
   by every return-type path) and four other `BuildContext` reads (the DML-element kind, the error-union
   members, and two `@nodeId` node lookups), plus `ServiceCatalog.getTableSqlNameForType` and
   `SourceRowDirectiveResolver`'s `@table`-return read. All of these are migrated to the registry-free
   look-ahead (`TypeBuilder.lookAheadVerdict`, threaded onto `BuildContext` as `ctx.typeBuilder`) or the
   pure fixed-point indices (`ctx.tables` / `ctx.nodes` / `ctx.errors`, the slice-3d indices). Field
   classification is therefore read-free in fact, not just within `FieldBuilder`; that is the actual
   precondition the collapse rests on.

   **The pre-walk leaf sweep, not a dedicated index.** Input types, scalars, and enums are reached only
   through argument / input coordinates the walk never descends, so the walk does not classify them; they
   are classified by a sweep that runs *before* the walk (in `prepareForWalk`), because field
   classification reads input / scalar / enum verdicts from `ctx.types` (`MutationInputResolver`,
   `EnumMappingResolver`, `ServiceCatalog`'s scalar binding), so they must already be registered when a
   field classifies, exactly as the two-pass world (which classified them before any field) had them. This
   subsumes the plan's "index enums / scalars up front" with no separate `EnumIndex` / scalar index: the
   pre-walk sweep is itself the reachability-independent, complete registration the index would have
   provided. The directive-ignored warnings and the multi-producer rejection demotions also move into
   `prepareForWalk`, so a rejected input reads as its `UnclassifiedType` demotion during the walk (the
   only composite the demotion touches is a directiveless object, whose `classifyType` verdict is null, so
   the walk never overwrites it). The candidate-name hint (the last `FieldBuilder` `ctx.types` read) is
   sourced off the schema's declared type names, not the partial registry.

   **Deferred: the field-relative input model.** Inputs stay classified by the pre-walk sweep with the
   existing global verdict (`@table`-on-input plus the `findReturnTablesForInput` aggregate), so slice 4
   is byte-identical. Classifying the input *after* its field's target (deriving table-boundness from the
   target, de-emphasising `@table`-on-input) is the one non-byte-identical change the plan flagged; it is
   split to its own item, R327, rather than landed here, keeping the collapse a pure structural delta.
   Reflection grounding stays eager (`resolveAll` in `prepareForWalk`): the indices call `classifyType`
   over all types and ground bindings as a side effect, so dropping the precondition would fight eager
   index construction, exactly the tension the plan anticipated; left as-is.

   Output byte-identical (truth table 448, full pipeline / compile / execution build green); structure
   delta: three traversals collapsed to one, `buildTypes` + the hand-off + the field loop deleted, the
   read-free precondition completed across `BuildContext` and the helper resolvers. Gated by the
   falsifiable acceptance test `SingleWalkClassificationOrderTest` (a deep target's type-classify trace
   record follows its discovering field's record; an eager type pass registers the type first and fails
   it).
5. **Immutable validate phase (inlines R318).** The global reductions register diagnostics into a
   diagnostic channel instead of demoting to `UnclassifiedType` / `UnclassifiedField`; the
   validator and LSP read diagnostics from there. Changes the error-carrier mechanism, not which
   schemas pass or fail. The diagnostic-channel design is pinned below.

### Diagnostic-channel design (the inlined R318)

**What stops mutating.** Exactly the post-classification *demotions*, the sites where a verdict
classification already produced is overwritten purely to surface an error:

- `validateNodeTypeIdUniqueness` (`TypeBuilder`) — `register(UnclassifiedType)` over a real
  `NodeType`.
- `rejectCaseInsensitiveTypeCollisions` (`GraphitronSchemaBuilder.java:581`) —
  `register(UnclassifiedType)` over the colliding real verdicts.
- the dangling-reference backstop (`GraphitronSchemaBuilder.java:444`) —
  `reclassify(UnclassifiedField)` over a real `OutputField`.
- `EntityResolutionBuilder` federation checks (`:108` / `:128` / `:154` / `:173`) —
  `register(UnclassifiedType)`.
- the carrier-shape scan, re-expressed here as the validation guard slice 3's design decision
  already identified it to be.

**What does not change.** Classification's own honest `UnclassifiedType` / `UnclassifiedField`
verdicts (unknown table, inert enum, unbound participant, directive conflict, the edge-decidable
orphan from slice 3) stay exactly as they are: "this type genuinely did not classify" *is* the
verdict, not a demotion. R318 retires the overwrites, not the variant.

**Where diagnostics live.** Generalise the channel R279 slice 4 already built:
`collectDomainReturnTypeConflicts` stopped demoting and stashes a `List<Rejection>` on
`GraphitronSchema` that `validateUniformDomainReturnType` drains. Slice 5 promotes that one-off into
a single diagnostic channel on the schema (a `Rejection` plus its type-name / field-coord and
`SourceLocation`), folding `domainReturnTypeConflicts` into it since the shape is identical. The
`BuildWarning` channel stays separate unless folding it in is near-free; warnings are not part of
this entanglement.

**How each reader consumes it.** The validator drains the channel into the same `ValidationError`
stream it emits today from the `validateUnclassifiedType` / `validateUnclassifiedField` passes, so
`validate()`'s output is unchanged. The LSP is insulated: `Diagnostics.java` reads
`ValidationReport.errors()` / `.warnings()`, never the registry verdicts, so it needs no change as
long as the `ValidationError` stream is preserved. Emission is already globally gated:
`GraphQLRewriteGenerator.validate()` throws `ValidationFailedException` before `generate()` runs, so
a field that stays classified but carries an error diagnostic never reaches the emitter. This is why
the dangling-reference demotion's second job (removing the field from emission so assembly cannot
choke) is redundant under this design and can be dropped: the assembled `GraphQLSchema` is built
from SDL up front (graphql-java has already validated SDL type references), and the dangling case is
a graphitron-emission concern the global gate forecloses. The slice must confirm nothing between the
builder and the validator (notably `rebuildAssembledForConnections`) reads the demoted verdict.

**Acceptance.** Identical `ValidationError` stream out; the registry verdict for every demoted case
now reflects what the type actually is (the verdict means one thing); generated output for
error-free schemas stays byte-identical. Truth table + sakila gate as for the other slices.
