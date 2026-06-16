---
id: R279
title: Field-first reachability-driven classification driver
status: In Progress
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-06-05
last-updated: 2026-06-16
---

# Field-first reachability-driven classification driver

Classification today is a strict-ordered multiphase build in
`GraphitronSchemaBuilder.buildSchema`: an eager type pass (`TypeBuilder.buildTypes`)
that classifies every named SDL type *in isolation*, then a field pass over *every*
`GraphQLObjectType` in `schema.getAllTypesAsList()` (no reachability sweep; graphql-java
parks every declared type into `additionalTypes`, so orphans are visited), then three
field->type back-fill post-passes that exist only because the type pass classified blind:
`registerNestingTypes`, `validateUniformDomainReturnType`, and `ConnectionPromoter.promote`
(which also rebuilds the assembled `GraphQLSchema`). (Verified 2026-06-14 against current
`buildSchema`: the eager `getAllTypesAsList()` field pass, then `registerNestingTypes`,
`validateUniformDomainReturnType`, `ConnectionPromoter.promote` /
`rebuildAssembledForConnections`, and the global post-walk sweeps `rejectDanglingTypeReferences`,
`rejectCaseInsensitiveTypeCollisions`, `MappingsConstantNameDedup`, `EntityResolutionBuilder`.
The fourth post-pass this paragraph once listed, `promoteSingleRecordPayloads`, **no longer
runs here**: R276/R178 folded single-record-carrier binding into the eager type pass itself
(`TypeBuilder.buildTypes` calls `promoteSingleRecordPayloads` after its second pass, binding
producer-backed directiveless carriers to a `JooqTableRecordType` via
`scanStructuralDmlPayload`), and orphan carriers are now *rejected* downstream rather than
promoted. Slice 3's fold-into-the-walk wording is updated to match.) The result is bidirectional
ordering coupling (type pass is a prerequisite for field classification; field classification
is a prerequisite for the post-passes), a `TypeRegistry` with four write verbs
(`classify`/`enrich`/`demote`/`synthesize`) to service the phasing, and "classify the type in
isolation" as the documented root smell behind R278.

## Direction

Replace the type-pass / field-pass / four-post-pass sequence with a single
reachability-driven, field-first classification walk, then validate, then emit. This is a
slice under the R222 (`dimensional-model-pivot`) umbrella and **supersedes** R166
(`graphqlschemavisitor-driven-emission`). It is a behaviour-preserving restructure of the
*driver*: it does not redesign how polymorphic types (interface/union) classify. The walk
classifies them exactly as the current type pass does (the existing `ParticipantRef`
model, unchanged), just triggered on demand from the reaching field. The separate question
of whether `ParticipantRef` is the right primitive at all (the proposal to drop it in
favour of sealed `TableUnionType` / `ErrorUnionType` variants) is R278's scope, orthogonal
to and not a prerequisite of this item. It supersedes R166 rather than implementing
it: R166 proposed a `GraphQLSchemaVisitor`-driven *emission* walk to fix per-emitter skip-
filter drift (the R165 bug class: N emitters each choosing their own skip rules, so
`FetcherRegistrationsEmitter` keeps an empty-bodied keyset entry, `ObjectTypeGenerator`
skips the method, `GraphitronSchemaClassGenerator` emits the call site, and the consumer's
`javac` fails) and the missing reachability sweep; here the visitor walk is
**classification only**, the reachability prune happens once at classification, and
emission (like validation) stays plain iteration over the already-pruned `GraphitronSchema`
and needs no per-emitter filters because the model only contains reachable, classified
things. R166's goals are met without a visitor-driven emitter, and its standalone
`ReachabilityPruner` post-pass alternative is obviated: reachability is structural here, not
a filter bolted onto `schema.types()`. R166's one orthogonal sub-thread, the typed
non-empty carrier for `FetcherRegistrationsEmitter`'s return (R166 Q7, originally R165),
spun out to R280 (`fetcher-bodies-nonempty-carrier`) since it is an emitter-return invariant
independent of how classification works.

Walk shape (converged in the originating discussion; not yet a frozen contract). The
**only** visitor-based phase is classification; validation and emission iterate the
classified `GraphitronSchema` and are not visitor-based.

- *Driver.* Seed `graphql.schema.SchemaTraverser` via
  `depthFirst(visitor.toTypeVisitor(), roots)`, where `visitor` is a
  `graphql.schema.visitor.GraphQLSchemaVisitor` (graphql-java 25; its
  `FieldDefinitionVisitorEnvironment` exposes `getContainer()` for the parent type and
  `getUnwrappedType()` for the base return type, the two inputs field classification
  needs). The graphql-java visitor machinery is already in-tree (`ConnectionPromoter` uses
  the lower-level `GraphQLTypeVisitorStub` + `SchemaTransformer` post-classification today).
  The seed set is **Query + Mutation roots plus a `@node`/`@key` directive scan**, not just
  the two roots:
  - `Query.node` / `Query.nodes` return the `Node` *interface*. With the interface fan-out
    in the child function (below) its `@node` implementors are reachable across the
    interface->implementor edge whenever a field reaches `Node`, but the directive scan still
    seeds them directly so reachability does not hinge on a `Query.node`/`Query.nodes` field
    being present, and so a single scan covers both `@node` and the federation `@key` case
    below.
  - Federation `_entities` / `_Entity` / `Query._entities` **do not exist at classification
    time**: Apollo injects them post-build in `GraphitronSchemaClassGenerator`. So
    `@key`-bearing entity types are not reachable through any field and must be seeded by
    directive scan, exactly as `EntityResolutionBuilder` gathers them today (the `@node` and
    `@key` seed sets largely coincide, since `KeyNodeSynthesiser` gives every `@node` a
    `@key`). The earlier "reach federation through the `Query._entities` field classifier"
    framing was wrong and is corrected here.
  - Subscription is recognised-but-unsupported (its root fields classify to
    `UnclassifiedField`), so it reaches no targets today; a future third seed.
- *Child function: the interface fan-out (an asymmetry that is load-bearing).* The walk's
  descent edges are not all native graphql-java child edges, so the traverser must be built
  with the `SchemaTraverser(Function<GraphQLSchemaElement, List<GraphQLSchemaElement>>)`
  constructor, **not** the no-arg default that the `depthFirst(visitor.toTypeVisitor(), roots)`
  form above implies. `GraphQLUnionType.getChildren()` includes its member types (`getTypes()`),
  so a field returning a union descends into every member for free. `GraphQLInterfaceType.getChildren()`
  does **not** include its implementors (only its own fields, the interfaces it implements, and
  directives); the only native edge is the reverse, implementor->interface
  (`GraphQLObjectType.getChildren()` lists `getInterfaces()`), which never fires unless the
  implementor is already reachable some other way. So the interface->implementor edge must be
  supplied: the custom child function returns, for a `GraphQLInterfaceType`, `iface.getChildren()`
  unioned with `schema.getImplementations(iface)`. This mirrors what `TypeBuilder` already does
  manually today (union enrichment reads `getTypes()`; interface enrichment reads
  `getImplementations(iface)` precisely because the traversal does not surface implementors).
  Supplying the edge in the child function rather than by pre-expanding the seed set is the
  correct choice, not a convenience: interface reachability is transitive and discovered late (an
  interface can first appear deep in the walk, e.g. `Query -> Foo -> field: InterfaceX`), and
  `getChildren` is applied lazily per node as each node is dequeued (`TraverserState.pushAll`
  calls `getChildren.apply(node)`), so the fan-out fires whenever an interface is reached at any
  depth, a reachability fixpoint for free; a one-shot seed pre-expansion would only catch
  interfaces named at the roots. The synthesised implementors are deduped against already-visited
  nodes and routed through `backRef`, so an implementor also reachable elsewhere is not
  double-visited. One caveat for the visitor: a synthesised implementor enters with the interface
  as its `TraverserContext` parent under a synthetic `NodeLocation`, so classification must not
  key off parent/location for these nodes (field classification reads `getContainer()` /
  `getUnwrappedType()` off the field environment, which is unaffected).
- *Fields drive types; types are never classified up front.* The premise that a type has a
  classification computable from the type alone is false, and is the root of today's multiphase
  mess: the information that classifies a type (a producer's reflected return class, a parent's
  table context, a payload's carrier shape) lives on the *fields that reach it*, not on the type.
  So there is no type pass. The walk visits fields; each field visit classifies the field and, as
  a byproduct, **registers** a classification for the field's target type. A field is visited once,
  so it is classified once; a type is reached by every field that returns it, so it is registered
  many times. This single rule replaces the eager type pass and all four post-passes.
- *The classifier is a pure producer; the schema accumulator owns multiplicity and reconciliation.*
  The classifier never reads back a prior verdict and never reasons about conflict: it registers
  what the current field implies for itself and for its target. The `GraphitronSchema` accumulator's
  contract is that a field or type coordinate may be registered more than once; reconciling repeated
  registrations is *its* job, not the classifier's. Reconciliation is three-way, not two: an
  *equal* repeat is idempotent; a *compatible* repeat is **merged** (the accumulator combines the two
  into a richer single value, e.g. the synthesised Connection / Edge / PageInfo arms union their
  federation `@tag` applications and OR `shareable` across the carriers that reach one name, so the
  cross-carrier tag union lives in `register`, never in the producer); an *incompatible* repeat (two
  different concrete classifications for one name) demotes the type to `UnclassifiedType`.
  **Demotion and merge are the schema's concern, not the classifier's**, the classifier does not know
  either exists. This collapses the `TypeRegistry` write verbs to one `register`, dissolves the
  on-demand re-entrancy/cycle guard (re-registration is normal; walk-level cycles are the traverser's
  visited-set), and makes the result order-independent (the accumulator's merge is commutative, so
  walk order cannot change a verdict). The legal type directives stay `@node` / `@table` / `@error`,
  and a polymorphic target additionally reads its participants' directives (the existing
  `ParticipantRef` model, unchanged).
- *What a verdict closes over: node SDL, reflection, and downward context only.* A field is
  classified from three inputs and no others: the SDL readable at the node (its own directives, its
  parent type's, its target type's directives and kind), reflection on any referenced Java method,
  and context passed *down* the walk from ancestors (next bullet). Critically, "is my target a
  table" is answered by reading the target's `@table` directive off the SDL, **not** by reading a
  prior classification verdict, so it needs no type pass to have run first; this is the direct
  refutation of today's `ctx.types.get(target) instanceof TableBackedType`. The completeness rule:
  every input to a verdict is `node SDL + reflection + downward context`; nothing is read
  *sideways* (a sibling's or another branch's verdict) or *back* (the accumulator). The construct
  in today's code that violates this is `TypeBuilder.findReturnTablesForInput`, an O(N) sideways
  back-scan; it dissolves into a local read at the field visit (see the arguments bullet below).
- *Down-the-walk context: two dimensions, never sideways.* The downward context rides on the
  traverser's `TraverserContext` vars (`setVar` on a node, `getVarFromParents` in a descendant),
  which flow ancestor->descendant and are invisible to siblings, exactly the "down, never sideways"
  discipline the completeness rule needs. It carries two dimensions:
  - *Query (scope) dimension.* The current SQL scope: whether we are inside an open scope, the
    `TableRef` it is rooted on, and the source context that owns it (`Unmapped` / `Table-mapped` /
    `Result-mapped`). This lets a field answer "am I part of an existing query, or do I open a new
    one." **Scope is corrected here from the legacy vocabulary:** `code-generation-triggers` says
    scope is "determined by the (source context, target type) pair," which cannot hold for a walk,
    the pair determines a *transition* (Enter / Split / record-handoff / Exit) and the resulting
    scope is the inherited scope with that transition applied. Scope membership is a function of the
    ancestor chain, not of the field alone. (This is the "scope/orthogonality claim" R8 flagged as
    the doc's actual defect.)
  - *DataFetcher dimension.* The source-side wiring context for the current subtree: the backing
    class a producer (`@service` / `@tableMethod`) established for the record type it returns, so
    that record's child accessors can reflect against it and decide whether they are valid, plus the
    source-key shape hints (`Wrap` / `Reader`, the dispatch-axis model). This is the
    producer->descendant propagation worked through in the originating discussion.
  These are *classify-time* context, distinct from R222's emit-time `QueryBuilder` /
  `DataFetcherBuilder` dimensional slots that share the dimension names; the context is what lets a
  producer *fill* those slots.
- *Target-model pivot (R305, 2026-06-15): this walk is the named substrate for source cardinality.*
  R222's field-side model pivoted after R279 was written: `Carrier` is now sealed, and its `Source`
  arm carries a **source cardinality** (`One | Many`) defined as **the product of all ancestor field
  cardinalities** along the path from the operation root (one `Many` ancestor makes every descendant
  `Many`). That is a path-accumulated, ancestor-chain property, the exact shape of this item's
  down-the-walk context: it rides the same `TraverserContext` `setVar`/`getVarFromParents` mechanism
  the Query-scope dimension uses (each field sets its own cardinality; a descendant reads the product
  over its ancestors), never sideways. R305 itself cannot compute it (no walk exists yet), so it
  ships the slot **hard-coded to `Many`** with the `One` inline-skip kept as dead code, and both R305
  and R308 (`service-list-payload-arrival`) explicitly defer the real ancestor-product to "R279's
  walk." R279 stays behaviour-preserving and does **not** compute source cardinality itself (that
  new behaviour is R308's scope); the adaptation is only that the downward-context design must *admit*
  ancestor-cardinality accumulation as a first-class rider so R308 can layer on it without re-walking.
  Keep it distinct from `SourceKey.Cardinality` (`SourceKey.java`): that is the local, target-side
  per-key row count (rows per source object), not the source-arrival product; the two answer
  different questions and must not derive from each other (R305 is emphatic on this).
- *Arguments and inputs are classified per field-usage, at the field visit.* The visitor visits
  fields only; it does not descend into arguments or input objects as separate events. An
  argument's classification is a function of the visited field: to bind argument `a`, read field
  `f`'s return type and bind `a`'s input fields against that return table, and when the return is a
  union or interface, against the participants' tables (reachable from the visit). The same input
  type used by two fields with different return tables is *not* a conflict to detect, each visit
  binds against its own field's return table, which is "different consumers, different POJOs" by
  default (R222's R98 absorption). This is the local read that replaces
  `TypeBuilder.findReturnTablesForInput`'s global back-scan, and it is why input-side
  classification needs no separate input-type pass.
- *Classify (deterministic) then validate (sorted) then emit.* The classify -> validate
  split stays; what collapses is the intra-classify multiphase. Classification must be
  order-independent; validation runs sorted for stable diagnostics. Both validation and
  emission iterate the classified `GraphitronSchema`.

**Model corrections (2026-06-16, from the slice-5 implementation).** Three points where earlier
prose (and a stale code comment) lagged the model above, recorded so the legacy two-phase frame does
not reappear:

- *A directiveless object is classified by the field that reaches it, never by a type pass reading it
  in isolation.* `TypeBuilder.classifyType` returning `null` for a directiveless object is the type
  pass **declining**, not a classification; the verdict comes from the reaching field's visit
  (a `NestingField` makes it a `NestingType`, an `@asConnection` / structural carrier makes it a
  `ConnectionType` / `EdgeType` / `PageInfoType`). The `TypeBuilder.buildTypes` pass still present
  after slices 3b-5 is transitional scaffolding (it reproduces the eager registry by iterating the
  walk's discovered set plus a compensating orphan sweep), not part of the model; slice 6 removes it.
- *There was never a `NestingType` -> `ConnectionType` promotion.* A connection carrier is never a
  `NestingField` (it classifies as a connection field), so a structural Connection / Edge is never a
  `NestingType`; it is unregistered until the carrier's visit synthesises it directly. The retired
  `ConnectionPromoter` comment claiming an enrich-from-`NestingType` described a path that never ran.
- *`register` owns reconciliation, including the federation `@tag` union and demotion.* Producers
  (connection synthesis included) register what the current field implies and nothing more; the
  three-way merge / idempotent / demote reconciliation is the accumulator's, per the pure-producer
  bullet above.

## Implementation plan (slicing)

The slicing is built for risk isolation and bisectability. Two constraints, both from the
design principles, shape it more than anything else:

- *The merge gate is the designated primary tier, not the differential.* The behavioural
  proof is the exhaustive `GraphitronSchemaBuilderTest` truth table (~370 enum rows over the
  sealed hierarchy as of 2026-06-14, drifted down from the ~398 this item was written against
  as R290 and adjacent leaf retirements removed rows; the count is `VariantCoverageTest`-enforced,
  so lean on that enforcement rather than the literal number) plus the sakila pipeline-tier `TypeSpec`
  assertions, the `graphitron-sakila-example` Java-17 compile, and the execution tier. A
  projection-snapshot differential (old-vs-new through `CatalogBuilder.buildSnapshot`, the
  one model surface that compares by value since `GraphitronSchema` carries identity-equality
  graphql-java nodes) is a *development bisect aid only*, never the gate: it is a lossy shadow
  (it flattens assembled-schema identity, `ErrorType` handler aggregation, raw node refs), and
  elevating it above the pipeline tier would pin the shadow, not the behaviour. There is no
  permanent dual-run flag inside the classifier; the inversion lands directly behind the
  truth-table + sakila tiers. (If a specific regression is found that those tiers cannot catch,
  that gap is closed by adding a pipeline assertion, not by a parallel classifier.)
- *No classifier invariant spans a slice boundary without a validator mirror.* Where a slice
  retires an enforcement mechanism, the replacement lands in the same commit.

Polymorphic-type classification is preserved unchanged throughout (the `ParticipantRef`
redesign is R278, separate). The documentation deliverable that earlier sat here as slice 0
(absorb `code-generation-triggers`, fold in R8's doc-as-index intent, fix the stale Javadoc
references) has been **eaten by R281 (`classification-test-dsl`)**, whose documentation-first
phase produces that prose against its executable example corpus; R279 no longer owns a doc slice.
Coupling that survives: when R279's inversion (slice 3) and orphan pruning (slice 6) land, the
R281 classification doc must be updated to match. **Truth-table baseline note (R290 / R305):** R290
(`datafetcher-field-dimensional-slots`, now **Done**) removed two `GraphitronSchemaBuilderTest`
enum rows (the `ConstructorField` verdict + its `@ProjectionFor` sibling) and the `constructor`
corpus fixture when it dissolved `ConstructorField`; that row delta is already absorbed in the
current ~370-row baseline, so R279 simply takes the tree as it stands. R305
(`collapse-singlerecordtablefield-into-recordtablefield`) is now **In Progress** and its scope grew
well past the original SRTF collapse: it lands the carrier pivot itself, sealing `Carrier` into
`Query`/`Mutation` (payload-less) + `Source(SourceShape, SourceCardinality)`, re-deriving re-fetch
from `Table mapping × holds-records`, asserting `sourceShape`/`sourceCardinality` in the R281 corpus,
and collapsing `SingleRecordTableField` into `RecordTableField`. It is actively churning the truth
table and corpus *right now*, so R279's primary merge gate is a moving baseline until R305 settles;
R279 still declares no hard ordering edge (it is behaviour-preserving over whatever rows exist), but
in practice it should rebase onto R305's landed corpus rather than race it. The new dependency runs
the other way and is real: R308 (`service-list-payload-arrival`, blocked by R305) and R305's own
dead `One` branch both consume **R279's walk** to compute the ancestor-product source cardinality
(see the *Target-model pivot* bullet under down-the-walk context). So R279 → R308 is a genuine
forward edge (R308 cannot build the `Many` arm without this walk), even though nothing blocks R279
itself. What remains here is the risk-isolated, gated code transformation, slices in landing order:

1. **Reachability observatory + differential bisect aid (additive, zero behaviour change).** *Shipped at `0650f3e`.*
   Build the `SchemaTraverser` walk that computes the reachable set (seed: Query + Mutation +
   `@node`/`@key` directive scan; descend field->target and union->members on native child
   edges, and interface->implementor on the synthesised edge supplied by the custom child
   function, see Driver) and the projection-snapshot comparator. The test asserts the *durable*
   invariant **reachable ⊆ classified** (every reachable type is classified, the safety property
   every later slice preserves) and separately *measures* the classified-but-unreachable orphan
   set as an inventory slice 6 will prune, phrased as an observation, not a correctness invariant.
   *As built:* `SchemaReachability` (main, the reusable walk), `ProjectionSnapshotComparator`
   (test-scoped bisect aid), `SchemaReachabilityTest` (pipeline tier). The child function follows
   exactly the output edges (field-output / union-member / interface->implementor) and not the
   native `getChildren()`, since native `getChildren()` would descend into arguments (which the
   field-first walk never does) and object->interface (which would over-approximate reachability);
   the reachable set includes the operation roots, which the ⊆ check excludes because a root's
   fields, not its type, are classified.
2. **Single-type registration entry (byte-identical output).** *Shipped at `ddd6e1f`.* Route the eager
   per-type path (the pass-1 `classify` and the pass-2 participant `enrich`) through one reconciling
   `register` entry on `TypeRegistry` that tolerates repeated registration: absent -> store; a repeat
   that agrees -> idempotent no-op; a demotion to `UnclassifiedType` or a same-kind enrichment ->
   replace; an *incompatible* repeat (two different concrete classifications) -> **throw**. Keep driving
   it eagerly over `getAllTypesAsList()` (the two-pass driver, `demote`/`synthesize`, and the cross-type
   passes stay on their explicit verbs) so output is byte-identical. *Honest scope (per a
   `principles-architect` read):* this delivers the reconciling write verb, **not yet** the
   order-independent entry point the inversion calls. The incompatible arm is a deliberate tripwire,
   not the real compatibility predicate: the eager two-pass provably never produces an incompatible
   repeat (every repeat is the enrich pass replacing a same-kind or rejected value), so the predicate
   is left to slice 3, written against the field walk's real competing-verdict inputs with its
   validator mirror in the same commit rather than guessed here as dead code. The absent/present axis
   is shaped so slice 5 can fold `ConnectionPromoter`'s hand-rolled enrich-or-synthesize fork into the
   same entry. **What slice 2 does *not* touch:** participant enrichment (`buildParticipantList`) still
   reads `ctx.types.get(participant)` sideways, so `register`'s *input* is still order-dependent;
   converting that to a participant-SDL-directive read is the order-independence step, broken out as
   slice 3's first gated commit (below). Gate: truth table + sakila, identical output (differential as
   bisect aid).
3. **The inversion.** **Slice 3a shipped (byte-identical, truth table 2101 + sakila execute-spec
   400, no diff).** `buildParticipantList` no longer reads `ctx.types.get(participant)`; it calls a
   new `participantClassification(typeName)` helper that recomputes the participant's verdict as a
   pure function of SDL plus the already-resolved reflection bindings, with no registry read. This
   is **not** a flat "read the `@table` directive" substitution; the registry-verdict branch keys on
   the participant's *full* classification (`TableBackedType` and-not-`TableInterfaceType` ->
   `TableBound` with `tbt.table()`; non-null non-`Unclassified` -> `Unbound`; `null` ->
   `Unbound`-if-`allowNonTableMembers` else error). *Correction to the scoping note:* `TableBackedType`
   permits only `TableType`, `NodeType`, and `TableInterfaceType`, so the `TableBound` arm is reached
   only by the `@table`-directive objects; `JooqTableRecordType` is a `ResultType`, so a producer-bound
   participant lands in the `non-null non-Unclassified -> Unbound` arm, not the `TableBound` arm. The
   helper reproduces exactly what `ctx.types.get` returned at enrich time by recomputing
   `classifyType(participant)`, with the two reconciled edge cases:
   (i) the enrich pass runs *before* `promoteSingleRecordPayloads`, so a directiveless carrier is
   `null` at enrich time under both the old read and `classifyType` (they agree);
   (ii) `surfaceMultiProducerRejections` may have demoted a participant to `UnclassifiedType` *before*
   the enrich pass, which the old registry read saw (-> error arm) but a fresh `classifyType` would
   not, so the helper consults `bindings.rejection(typeName)` first and returns an `UnclassifiedType`
   when present. `classifyType` is a value-builder over SDL + bindings + catalog with no registry or
   accumulator writes, so re-invoking it is safe (its only side effect anywhere is a rare `LOGGER.warn`
   for a `@node` keyColumns order mismatch, which does not affect generated output). The verb that was
   `register`-in-name-only in slice 2 is now backed by an order-independent participant read, ready for
   the walk to call.
   *Then the flip:* replace the eager type pass + all-objects field pass with the field-first
   walk: seed, visit fields, classify each field, and register its target type as a byproduct
   (slice 2's entry). No on-demand recursion and no re-entrancy guard, registration is the only
   write and the accumulator absorbs repeats; walk-level cycles are the traverser's visited-set.
   Fold the directiveless-from-field cases into the walk: `registerNestingTypes` (still a
   post-pass) and the single-record-carrier binding that `TypeBuilder.promoteSingleRecordPayloads`
   now performs *inside* the eager type pass this slice replaces (producer-backed carriers bind to
   a `JooqTableRecordType` via `scanStructuralDmlPayload`; orphan carriers stay unbound and are
   rejected downstream, so the walk reproduces today's reject-the-orphan behaviour rather than
   promoting it). **Keep
   `validateUniformDomainReturnType`'s demote-to-`UnclassifiedField` intact through this slice**
   so no enforcement gap opens; it is retired in slice 4 when its replacement goes green. Gate:
   truth table + sakila pipeline `TypeSpec` + compile + execute. No dual-run flag.

   **Slice 3b shipped (truth table 2116 + sakila TypeSpec/compile/execute 404, all green).** A
   `principles-architect` read on the design fork (interleaved single-pass DFS that folds the
   carrier-binding into the walk, vs a two-phase reachability-driven driver that preserves the
   type-classify->promote->field-classify ordering) steered to the two-phase form: the
   producer->carrier relationship the interleaved fold would discover by descent is *already* a
   fixed point in `RecordBindingResolver` (so the DFS buys no real ordering fidelity), while
   folding `validateNodeTypeIdUniqueness` (a global cross-type fold, sibling to slice 5's
   case-fold sweep) and `surfaceMultiProducerRejections` (keyed off the order-independent binding
   rejection) into walk order would pay reordering risk in the most delicate code for no gain.
   What landed:
   - *Type driver (`TypeBuilder.buildTypes`).* The eager "classify every `getAllTypesAsList()`
     type in isolation" pass-1 is replaced by iterating the field-first walk's discovered set
     (`SchemaReachability.reachableTypeNames`, the slice-1 walk built for exactly this reuse):
     each reached type is classified as a byproduct of the field edge that reached it, the verdict
     still `classifyType`. A **compensating orphan sweep** then classifies every named type the
     walk did *not* reach (input types, scalars, enums, and unreachable output objects such as
     `OrphanCat`), so the registry content stays identical to the eager pass. The enrich pass,
     `surfaceMultiProducerRejections`, `validateNodeTypeIdUniqueness`, and
     `promoteSingleRecordPayloads` keep their exact relative ordering as post-passes over the
     registry; `emitDirectiveIgnoredWarning` moved to its own SDL-order pass so warning order is
     unchanged.
   - *Field driver (`GraphitronSchemaBuilder.buildSchema`).* The all-objects field pass body is
     extracted verbatim into `classifyFieldsOfObject`; it is now driven over the reachable object
     types (walk order) followed by a compensating sweep over the unreached object types. Union of
     the two = every object type, so field-registry content is behaviour-identical.
   - *Honest scope.* This is approach (B): the driver is inverted (fields discover the classified
     set) while the two compensating sweeps keep orphans classified so the slice is
     behaviour-preserving on the gates. Slice 6 deletes both sweeps (narrowing the type sweep to
     non-output-object types so inputs/scalars/enums stay classified), at which point an
     unreachable `@table` object is no longer classified and the reachability prune becomes
     observable behaviour. The true single-pass DFS fold (approach A) is deferred to a
     behaviour-preserving simplification over the already-inverted driver. `registerNestingTypes`
     and `validateUniformDomainReturnType` are untouched, as planned.
4. **`DomainReturnType` on the field; retire `validateUniformDomainReturnType`.** Move
   `DomainReturnType` onto the field and replace the post-pass agreement check with a validator
   rule, *model change and validator rule in one commit* so no enforcement gap opens. (This is
   the directiveless-types-inherit-from-fields invariant made structural: the walk's
   compatible-or-demote already covers it; this slice removes the now-redundant post-pass.)
   Gate: truth table + sakila.

   **Slice 4 shipped (truth table 2116 + sakila TypeSpec/compile/execute 404, all green).**
   `DomainReturnType` is already on the field (an abstract `OutputField.domainReturnType()` every
   leaf answers, materialised by R290), so the slice's substance was relocating *enforcement* off
   the builder's reclassifying post-pass and onto a validator rule, in one commit, with no
   intervening gap. What landed:
   - *Model change (`GraphitronSchema`).* A new `List<Rejection> domainReturnTypeConflicts`
     component carries the multi-producer disagreements, shape-parallel to the existing
     `contextArguments().conflicts()` carrier and defaulted to empty for every test-constructed and
     conflict-free schema.
   - *Builder (`GraphitronSchemaBuilder`).* The reclassifying `validateUniformDomainReturnType`
     post-pass became `collectDomainReturnTypeConflicts`: it runs at the *same point* (on the
     pre-promotion, pre-dangling field registry, against the assembled schema's SDL-Object axis via
     the retained `sdlReturnTypeName`), so the conflict set is byte-identical, but it now returns one
     `MultiProducerDomainTypeDisagreement` per conflict group instead of demoting the producers to
     `UnclassifiedField`. The list is stashed on the model.
   - *Validator rule (`GraphitronSchemaValidator`).* A new `validateUniformDomainReturnType` drains
     the cached conflicts into `ValidationError`s, mirroring `validateContextArgumentTypeAgreement`
     exactly. The producers now stay classified through the build and the validator halts it before
     any generation runs, so the net build pass/fail and the rejection message (payload SDL type +
     all participant coords + both `DomainReturnType` arms) are preserved.
   - *Honest scope.* Behaviour-preserving at the build pass/fail level; the *internal*
     representation of a rejected schema changed (classified producers + a validator error, rather
     than demoted `UnclassifiedField`s), so the two `SingleRecordTableFieldServiceProducerPipelineTest`
     mixed-producer cases were updated to assert the validator-surfaced conflict instead of the
     builder-side demotion. The "walk's compatible-or-demote already covers it" framing in the slice
     header is the aspirational end-state (slices 5/6+); the walk does not yet demote on
     field-level multi-producer conflicts, which is exactly why the enforcement was moved to a live
     validator rule rather than simply deleted.
5. **Fold ConnectionPromoter into the walk.** Synthesise `Connection`/`Edge`/`PageInfo` into the
   registry on demand when an `@asConnection` field is visited. Make
   `rebuildAssembledForConnections` a *pure function of the walk's synthesised-type set* (a typed
   parameter, not a second `schema.types()` scan) so there is one producer and the rebuilt
   assembled schema cannot drift from the registry, the R165 bug class relocated to Connection
   types otherwise. Add a pipeline assertion covering the assembled-schema delta (the
   differential's blind spot). Delete the now-dead phases; keep `rejectCaseInsensitiveTypeCollisions`
   as a post-walk registry sweep (a global cross-type check, not field-driven).

   **Slice 5 shipped (truth table 2115 + sakila execute-spec 404, all green).** Connection synthesis
   is folded into the field-first walk and the synthesised-type set has a single producer. What landed:
   - *Field-first synthesis (`ConnectionPromoter`).* The standalone all-types `promote(BuildContext)`
     pass (its own `getAllTypesAsList()` scan) is replaced by `synthesiseForField`, called once per
     visited field at the top of `classifyFieldsOfObject` (before its classification early-returns, so
     it fires for every field exactly as the retired scan did, including carriers on directiveless
     parents). Visiting an `@asConnection` / structural carrier registers its Connection / Edge /
     PageInfo as a byproduct; there is no sibling scan and no dedup/union logic in the promoter.
   - *Tag union + reconciliation in the accumulator (`TypeRegistry.register`).* The cross-carrier
     federation `@tag` union (carriers sharing a connection name; every carrier feeding the one shared
     `PageInfo`) now lives in `register`'s same-kind reconciliation for the synth arms, alongside the
     planned throw→demote-to-`UnclassifiedType` on an incompatible repeat (validator-mirrored by the
     unclassified-type pass). The hand-rolled enrich-or-synthesize fork and the `unionTagsIntoExisting`
     post-pass are gone, and the now-dead `synthesize` / `enrich` verbs are retired (`classify` /
     `demote` stay, still used by non-connection passes).
   - *Single-producer rebuild (`rebuildAssembledForConnections`).* It takes the typed
     `List<GraphQLObjectType>` synthesised set (resolved once, post-walk, from the names the walk
     flagged) instead of re-deriving it from a second `ctx.types` scan, so the assembled schema cannot
     drift from the registry (the R165 two-producers bug class, here relocated to Connection types, is
     closed by construction). `ConnectionAssembledDeltaPipelineTest` pins that delta, the projection
     differential's blind spot.
   - *Behaviour-preservation guard.* Because synthesis now registers a structural Connection / Edge /
     PageInfo *during* the walk, `classifyFieldsOfObject` skips a parent that is a connection arm
     (in addition to the directiveless `null` skip), reproducing the retired flow where those types
     were still directiveless at field-classification time and their fields were never classified
     standalone (the connection emitter owns them).
   - *Honest scope.* The synth arms' `shareable` flag is now OR-ed uniformly on a same-name merge
     (previously first-write-wins for Connection / Edge, OR only for `PageInfo`); no gate exercises a
     same-name `@shareable` divergence, so this is unobservable, and OR is the more correct federation
     contract. The literal full verb-collapse (`classify` / `demote` into `register`) and the orphan
     prune remain for slice 6; this slice keeps `rejectCaseInsensitiveTypeCollisions` as a post-walk
     sweep as specified.
6. **Prune orphans and make the walk the sole driver (the payoff, intended behaviour change).** Stop
   classifying unreachable types; the walk already only reaches the reachable surface. Delete the
   transitional `TypeBuilder.buildTypes` scaffolding and the two compensating sweeps (narrowing the
   type sweep to non-output-object types so inputs / scalars / enums stay classified), so the
   field-first walk is the only classifier, not a driver layered over the eager pass. Flip slice 1's
   orphan *measurement* into an assertion that the orphan set is empty (or rejected), and update any
   truth-table rows that relied on orphan classification. With the post-passes that still hold the
   last `classify` / `demote` callers folded in (nesting registration becomes a walk byproduct; the
   case-fold collision sweep is the lone surviving global post-walk check), complete the verb-collapse
   so `register` is the only `TypeRegistry` write verb, the Q2 end state. Gate: truth table + sakila.

   **Slice 6 shipped in two gated commits.** *Part 1 (prune):* the compensating type sweep narrows to
   skip output composites (objects / interfaces / unions); the compensating field-pass sweep is
   deleted; an unreachable output composite is no longer classified, so the prune is observable. Slice
   1's orphan measurement became the invariant "every classified output composite is reachable"
   (`OrphanCat` is pruned). *Part 2 (verb-collapse):* `register` is the sole `TypeRegistry` write verb;
   `classify` / `demote` are removed and every caller (nesting registration, case-fold sweep,
   node-typeId uniqueness, multi-producer rejection, federation entity demotion, single-record-carrier
   binding) routes through `register`, which derives the trace `Op` from the reconciliation arm so the
   per-call observability is preserved. The "true single-pass DFS fold" (approach A: type classification
   inlined into the field walk) stays deferred as the spec's slice-3b honest scope set out; the driver
   is field-first and the walk is the sole classifier, but `TypeBuilder.buildTypes` still hosts the
   type-classify loop over the walk's reachable set rather than registering each type at its reaching
   field's visit. Gate: truth table 2112 + sakila execute-spec 404, full pipeline BUILD SUCCESS.

   **Two reachability-model corrections the prune surfaced (folded into `SchemaReachability`).** Slice
   1's edge set was incomplete for pruning, latent until orphans were actually removed: (1) a reachable
   object now reaches its implemented interfaces (the object -> interface edge slice 1 deliberately
   omitted as "over-approximation"). It is a structural necessity, not over-approximation: a `@node` /
   `@key` implementor can be seeded directly while the `Node` interface it implements is returned by no
   field (the federation fixture), and without the edge the interface is pruned and the emitted
   `implements` clause dangles, failing schema assembly. The interface -> implementor edge it pairs with
   means reaching one implementor pulls in the interface's siblings, which is acceptable (they are
   needed for polymorphic resolution and were classified before). (2) Subscription is now a seed: the
   root type classifies as a `RootType` so the schema-class generator can route the subscription entry
   point, while its fields still classify to `UnclassifiedField` and reach no supported targets (the
   "future third seed" note is resolved to root-classification-only).

## Open design questions (defer to implementation slices)

A `principles-architect` read on this slicing landed (its findings are folded into the slice
order and the gate posture above). The model above (pure-producer classifier + reconciling schema
accumulator, downward-only context) resolves the four forks earlier tracked here:

- *Re-entrancy and cycles* (was Q1): dissolved. The classifier registers rather than recursing on
  demand, so there is no caller-side recursion to guard; re-registration is normal and walk-level
  cycles are the traverser's visited-set.
- *`TypeRegistry` write-verb collapse* (was Q2): resolved. One `register`; demotion is the
  accumulator's internal reaction to an incompatible repeat, not a verb the classifier calls;
  `classify` / `enrich` / `synthesize` / `demote` dissolve into it. The residual detail for
  slices 2-3 is the exact compatibility predicate (when two directiveless-derived registrations are
  "compatible"), which lives in the accumulator.
- *Determinism* (was Q3): resolved structurally. The accumulator's merge is commutative and the
  classifier reads only SDL + reflection + downward context, so no verdict depends on walk order;
  no fixed-seed-order reliance is needed.
- *Input-side classification* (was Q4): resolved. Arguments are classified per field-usage at the
  field visit from the return type and participants; the back-scan dissolves (see the arguments
  bullet in the walk shape).

Genuine residual forks:

1. **Test ergonomics.** Unit tests call `FieldBuilder.classifyField` / `TypeBuilder` directly; a
   walk-driven classifier needs either a one-seed in-test driver or a direct-call shim.
2. **Reuse boundary.** `FieldBuilder.classifyQueryField` / `classifyMutationField` and the
   per-field dispatch are reusable as-is (both still present and private as of 2026-06-14); the
   change is the driver, reachability, and registration, not the ~5660 lines of per-field logic in
   `FieldBuilder`. Confirm the blast radius stays bounded to the driver.

The LSP `TypeClassification` / `FieldClassification` projections are read-only consumers
of the classified model and should be unaffected.
