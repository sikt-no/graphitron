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

> **Status (resume pointer).** Slices 1, 2, 3a and 3b are shipped to trunk; slice 3c (edge-decidable
> orphan to the edge) is the active front, then 4 (collapse to one walk, delete `buildTypes`) and 5
> (immutable validate). Slice 2's
> `NodeIndex` (`ctx.nodes`) is SDL-derived (via the producers) restricted to the reachable set,
> excludes only typeId-collision groups, and is **one-to-many by table** (a table may back several
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
- **Orphan handling stays split.** The edge produces `UnclassifiedField` directly for an
  edge-decidable orphan (no reclassify, which is already the immutable-validate end shape for free).
  But the whole-registry dangling-reference *backstop* stays a validate-phase reduction: a target
  that looks orphaned mid-walk may be rescued by a later nesting or connection edge, so a per-edge
  final demotion is not sound. Under the immutable validate phase the backstop registers a
  diagnostic rather than demoting.

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
once (identity dedup), but this is irrelevant because every type's registry entry is
*parent-independent* and all parent-relative context lives on the per-site field. A directiveless
projection registers a bare `NestingType` *marker* that carries **no table** (`(name, location,
schemaType)`); registering it once per incoming field is N identical writes the registry collapses
trivially. The projection's fields live on each embedding `NestingField` (`nf.nestedFields()`),
classified in that parent's table context, never in the global field registry under the shared type
name, so they never collide and are free to diverge per site. **The guard the implementation must
hold:** a shared type's registry entry stays parent-independent; the moment a `TableRef` or source
shape lands on it, the N-identical-writes property breaks. Parent context lives on the field.

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
3c. **Move the edge-decidable orphan verdict to the edge.** The edge produces `UnclassifiedField`
   directly for an edge-decidable orphan (no reclassify). The whole-registry dangling-reference
   *backstop* stays a post-walk reduction: a target that looks orphaned mid-walk may be rescued by a
   later nesting or connection edge, so a per-edge final demotion is not sound. Unreachable output
   types shift from classified to pruned (the warning is R319). Output byte-identical for the reachable
   verdicts; pinned by an explicit orphan test.
4. **Collapse to one walk; delete `buildTypes`.** Replace the no-op `GraphQLTypeVisitorStub` in
   `SchemaReachability` with a real `GraphQLTypeVisitor` that classifies types and fields in one
   `SchemaTraverser.depthFirst` call, parent context flowing down the var channel and the `NodeIndex`
   threaded in as a traverser argument (read-free invariant); delete `buildTypes`, the separate
   `classifyFieldsOfObject` loop, and the `reachableOutputTypes` hand-off; update the
   `buildContextForTests` seam; make reflection grounding on-demand (drop the `resolveAll()`
   precondition). Only validation reductions after. Output byte-identical; structure delta is the whole
   point, gated by the falsifiable acceptance test (no type registered before its discovering field is
   visited), which the current eager pass fails.
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
