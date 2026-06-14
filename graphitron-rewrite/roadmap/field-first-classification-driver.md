---
id: R279
title: Field-first reachability-driven classification driver
status: Ready
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-06-05
last-updated: 2026-06-07
---

# Field-first reachability-driven classification driver

Classification today is a strict-ordered multiphase build in
`GraphitronSchemaBuilder.buildSchema`: an eager type pass (`TypeBuilder.buildTypes`)
that classifies every named SDL type *in isolation*, then a field pass over *every*
`GraphQLObjectType` in `schema.getAllTypesAsList()` (no reachability sweep; graphql-java
parks every declared type into `additionalTypes`, so orphans are visited), then four
field->type back-fill post-passes that exist only because the type pass classified blind:
`registerNestingTypes`, `validateUniformDomainReturnType`, `ConnectionPromoter.promote`
(which also rebuilds the assembled `GraphQLSchema`), and `promoteSingleRecordPayloads`.
The result is bidirectional ordering coupling (type pass is a prerequisite for field
classification; field classification is a prerequisite for the four post-passes), a
`TypeRegistry` with four write verbs (`classify`/`enrich`/`demote`/`synthesize`) to
service the phasing, and "classify the type in isolation" as the documented root smell
behind R278.

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
  registrations is *its* job, not the classifier's. Compatible repeats agree (idempotent); an
  incompatible repeat demotes the type to `UnclassifiedType`. **Demotion is the schema's concern,
  not the classifier's**, the classifier does not know demotion exists. This collapses the four
  `TypeRegistry` write verbs to one `register`, dissolves the on-demand re-entrancy/cycle guard
  (re-registration is normal; walk-level cycles are the traverser's visited-set), and makes the
  result order-independent (the accumulator's merge is commutative, so walk order cannot change a
  verdict). The legal type directives stay `@node` / `@table` / `@error`, and a polymorphic target
  additionally reads its participants' directives (the existing `ParticipantRef` model, unchanged).
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

## Implementation plan (slicing)

The slicing is built for risk isolation and bisectability. Two constraints, both from the
design principles, shape it more than anything else:

- *The merge gate is the designated primary tier, not the differential.* The behavioural
  proof is the exhaustive `GraphitronSchemaBuilderTest` truth table (~398 enum rows over the
  sealed hierarchy, `VariantCoverageTest`-enforced) plus the sakila pipeline-tier `TypeSpec`
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
R281 classification doc must be updated to match. **Truth-table baseline note (R290):** R290
(`datafetcher-field-dimensional-slots`) removes two `GraphitronSchemaBuilderTest` enum rows
(the `ConstructorField` verdict + its `@ProjectionFor` sibling) and the `constructor` corpus
fixture when it dissolves `ConstructorField` and collapses `SingleRecordTableField`. R279 leans on
that truth table as its primary merge gate, so whichever item lands second re-baselines against the
other's row delta; this is a rebase, not a semantic conflict (R279 is behaviour-preserving over
whatever rows exist, and R290's two-leaf removal is independent of R279's orphan pruning). No
ordering edge is declared; landing R290 first is the cleaner order. What remains here is the
risk-isolated, gated code transformation, slices in landing order:

1. **Reachability observatory + differential bisect aid (additive, zero behaviour change).**
   Build the `SchemaTraverser` walk that computes the reachable set (seed: Query + Mutation +
   `@node`/`@key` directive scan; descend field->target and union->members on native child
   edges, and interface->implementor on the synthesised edge supplied by the custom child
   function, see Driver) and the projection-snapshot comparator. The test asserts the *durable*
   invariant **reachable ⊆ classified** (every reachable type is classified, the safety property
   every later slice preserves) and separately *measures* the classified-but-unreachable orphan
   set as an inventory slice 6 will prune, phrased as an observation, not a correctness invariant.
2. **Single-type registration entry (byte-identical output).** Extract per-type classification
   (and participant enrichment) from the eager loop into a single `register`-a-type entry on the
   schema accumulator that tolerates repeated registration (idempotent on a compatible repeat),
   but keep driving it eagerly over `getAllTypesAsList()` so output is unchanged. This decouples
   "how a type is classified" from "who triggers it," creating the entry point field classification
   will call as a byproduct. Gate: truth table + sakila, identical output (differential as bisect aid).
3. **The inversion.** Replace the eager type pass + all-objects field pass with the field-first
   walk: seed, visit fields, classify each field, and register its target type as a byproduct
   (slice 2's entry). No on-demand recursion and no re-entrancy guard, registration is the only
   write and the accumulator absorbs repeats; walk-level cycles are the traverser's visited-set.
   Fold `registerNestingTypes` and
   `promoteSingleRecordPayloads` into the walk (the directiveless-from-field case). **Keep
   `validateUniformDomainReturnType`'s demote-to-`UnclassifiedField` intact through this slice**
   so no enforcement gap opens; it is retired in slice 4 when its replacement goes green. Gate:
   truth table + sakila pipeline `TypeSpec` + compile + execute. No dual-run flag.
4. **`DomainReturnType` on the field; retire `validateUniformDomainReturnType`.** Move
   `DomainReturnType` onto the field and replace the post-pass agreement check with a validator
   rule, *model change and validator rule in one commit* so no enforcement gap opens. (This is
   the directiveless-types-inherit-from-fields invariant made structural: the walk's
   compatible-or-demote already covers it; this slice removes the now-redundant post-pass.)
   Gate: truth table + sakila.
5. **Fold ConnectionPromoter into the walk.** Synthesise `Connection`/`Edge`/`PageInfo` into the
   registry on demand when an `@asConnection` field is visited. Make
   `rebuildAssembledForConnections` a *pure function of the walk's synthesised-type set* (a typed
   parameter, not a second `schema.types()` scan) so there is one producer and the rebuilt
   assembled schema cannot drift from the registry, the R165 bug class relocated to Connection
   types otherwise. Add a pipeline assertion covering the assembled-schema delta (the
   differential's blind spot). Delete the now-dead phases; keep `rejectCaseInsensitiveTypeCollisions`
   as a post-walk registry sweep (a global cross-type check, not field-driven).
6. **Prune orphans (the payoff, intended behaviour change).** Stop classifying unreachable types;
   the walk already only reaches the reachable surface. Flip slice 1's orphan *measurement* into
   an assertion that the orphan set is empty (or rejected), and update any truth-table rows that
   relied on orphan classification.

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
   per-field dispatch are reusable as-is; the change is the driver, reachability, and registration,
   not the ~5400 lines of per-field logic. Confirm the blast radius stays bounded to the driver.

The LSP `TypeClassification` / `FieldClassification` projections are read-only consumers
of the classified model and should be unaffected.
