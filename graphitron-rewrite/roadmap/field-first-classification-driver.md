---
id: R279
title: Field-first reachability-driven classification driver
status: Spec
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-06-05
last-updated: 2026-06-05
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
slice under the R222 (`dimensional-model-pivot`) umbrella. It **absorbs** R278
(`classify-polymorphic-types-in-field-context`, now retired into the "Polymorphic types
and participants" section below) and **supersedes** R166
(`graphqlschemavisitor-driven-emission`); R279 is the field-context classification
mechanism R278 called for. It supersedes R166 rather than implementing
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
  - `Query.node` / `Query.nodes` return the `Node` *interface*; concrete `@node`
    implementors are reachable only across the interface->implementor edge, which a
    field-return descent does not traverse, so every `@node` type must be seeded.
  - Federation `_entities` / `_Entity` / `Query._entities` **do not exist at classification
    time**: Apollo injects them post-build in `GraphitronSchemaClassGenerator`. So
    `@key`-bearing entity types are not reachable through any field and must be seeded by
    directive scan, exactly as `EntityResolutionBuilder` gathers them today (the `@node` and
    `@key` seed sets largely coincide, since `KeyNodeSynthesiser` gives every `@node` a
    `@key`). The earlier "reach federation through the `Query._entities` field classifier"
    framing was wrong and is corrected here.
  - Subscription is recognised-but-unsupported (its root fields classify to
    `UnclassifiedField`), so it reaches no targets today; a future third seed.
- *Fields drive types.* Each visited field is classified or registered as an
  `UnclassifiedField`. Classifying a field triggers classification of its target type
  *on demand* (memoised), which replaces both the eager type pass and the four post-passes.
- *Type classification splits on whether the target carries a directive.* A type is a
  single per-name classification.
  - *Directive-bearing types are a pure function of the target.* The only legal type
    directives are `@node` / `@table` / `@error`; the type classifies from its own
    directives, and a polymorphic type additionally reads its *participants'* directives
    (`ParticipantRef` gains the `Error` permit R278 flagged, so participants classify
    `TableBound` / `Unbound` / `Error`). Same target, same verdict, regardless of who
    points at it.
  - *Directiveless types inherit their classification from the fields pointing at them.*
    They fall out as `NestingType`, payload carrier, etc. from the reaching field, so the
    verdict is field-derived, not target-intrinsic. A second field that re-derives a
    *compatible* classification is idempotent; an *incompatible* one triggers an automatic
    demote to `UnclassifiedType` at classification time, which the validator surfaces as
    the deterministic conflict error. (Order-independent: compatible derivations agree
    regardless of arrival order, and any incompatible pair demotes the same way.)
- *Population strategy leaves type classification entirely.* Whether a non-`@table`
  polymorphic member is *legal* depends on how the returning field populates the type
  (generated SQL polymorphism requires all members table-bound; service/reflection
  dispatch does not). That validity check moves to the validator in field context
  (closing R278), keeping the type verdict pure.
- *Arguments and inputs are read from the field definition, not walked.* The visitor
  visits fields only. Argument interpretation is a function of the field's classification,
  and everything the classifier needs about arguments and the input-type trees they reach
  is read directly off the `GraphQLFieldDefinition` while classifying the field; the walk
  does not descend into arguments or input objects as separate visit events.
- *Classify (deterministic) then validate (sorted) then emit.* The classify -> validate
  split stays; what collapses is the intra-classify multiphase. Classification must be
  order-independent; validation runs sorted for stable diagnostics. Both validation and
  emission iterate the classified `GraphitronSchema`.

## Polymorphic types and participants (absorbed from R278)

R276 made type classification reflection-only and left directiveless objects unclassified
at the type pass, which surfaced that the participant / polymorphic-type model classifies
types *in isolation* rather than in the context of the returning field. The field-first
walk is the mechanism that fixes this; the specifics R278 worked out, to fold into the
Spec:

- *Participant kinds.* `ParticipantRef permits TableBound, Unbound` today overloads
  `Unbound` with two unrelated meanings (an `@error` member vs a directiveless plain-
  interface implementor). Add a dedicated `Error` participant kind so a member classifies
  `TableBound` / `Unbound` / `Error` from its directive, and retire the overloaded
  `Unbound` arm.
- *Population strategy is the field's, and it decides admissibility and `TypeResolver`
  dispatch.* Generated SQL polymorphism reads members off a `__typename` column from a
  multi-table query, so every member must be table-bound; service/reflection population
  dispatches by runtime source class, so a non-`@table` member is fine. The field carries
  the strategy; the validator enforces member admissibility against it. This lets
  `buildParticipantList` shed its `allowNonTableMembers` flag.
- *An all-`@error` union is an `ErrorType`.* A type with `@error` is an `ErrorType`, and a
  union of `ErrorType` is too. Today it is a `UnionType` with `@error` `Unbound`
  participants, special-cased downstream by `GraphitronSchemaClassGenerator`'s `isErrorUnion`
  fork plus a source-class `TypeResolver`. Classify it as an `ErrorType` (aggregating the
  members' handlers, keyed per member type for the `TypeResolver` dispatch) and let the
  emitter read it off the `ErrorType` instead of re-deriving the shape.
- *Service/reflection-populated non-error polymorphic types are an unhandled gap to
  specify or explicitly reject.* `TypeBuilder.classifyType` never consults
  `bindings.resolveResult` for interfaces/unions (unlike objects), so a polymorphic type
  returned by an `@service` is just a plain `InterfaceType` / `UnionType`. The emitter has
  only three `TypeResolver` strategies (TableInterfaceType discriminator column; plain
  InterfaceType/UnionType `__typename` column from a SQL result; `@error`-only source-
  class) and **none** for a service/reflection-populated non-error polymorphic type, whose
  runtime objects carry no `__typename` column. The field-context classification makes this
  gap explicit; the Spec must either implement the strategy or reject the construct at
  validate time.
- *Cleanup markers.* The `R278` markers in `TypeBuilder` and `ParticipantRef`, the pre-R276
  `buildParticipantList` behaviour (`@table` -> `TableBound`; non-`@table` -> `Unbound`
  when `allowNonTableMembers` else error; `@error` riding the second `Unbound` arm), and
  the `allowNonTableMembers` flag are the concrete undo targets.

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

Slices, in landing order:

1. **R278 pure-model pieces, under the old driver.** Add the `ParticipantRef.Error` permit and
   retire the overloaded `Unbound` `@error` meaning; classify an all-`@error` union/interface as
   `ErrorType` (aggregating member handlers keyed per member for the `TypeResolver`), updating
   `GraphitronSchemaClassGenerator`'s `isErrorUnion` fork and the validator. This lands *first*
   because the on-demand type classification in slice 4 *consumes* the `Error` permit (the
   dependency arrow points this way), and landing it under the known-good old driver isolates
   "did the new variant classify right" from "did the new driver classify right." Decide the
   service/reflection-populated non-error polymorphic gap here; safe default per "validator
   mirrors classifier" is a validate-time rejection (`STUBBED_VARIANTS`-style build failure)
   unless the `TypeResolver` strategy is implemented. Keep `allowNonTableMembers` for now (it is
   field-context-dependent; retired in slice 5). Gate: truth table (`ErrorTypeCase`) + sakila.
2. **Reachability observatory + differential bisect aid (additive, zero behaviour change).**
   Build the `SchemaTraverser` walk that computes the reachable set (seed: Query + Mutation +
   `@node`/`@key` directive scan; descend field->target, interface->implementor,
   union->members) and the projection-snapshot comparator. The test asserts the *durable*
   invariant **reachable ⊆ classified** (every reachable type is classified, the safety property
   every later slice preserves) and separately *measures* the classified-but-unreachable orphan
   set as an inventory slice 7 will prune, phrased as an observation, not a correctness invariant.
3. **On-demand memoised single-type classification (byte-identical output).** Extract per-type
   classification (and participant enrichment) from the eager loop into a memoised single-type
   entry, but keep driving it eagerly over `getAllTypesAsList()` so output is unchanged. This
   decouples "how a type is classified" from "when," creating the entry point the walk calls.
   Gate: truth table + sakila, identical output (differential as bisect aid).
4. **The inversion.** Replace the eager type pass + all-objects field pass with the field-first
   walk: seed, visit fields, classify each field, classify its target on demand (slice 3's
   entry) with the re-entrancy/cycle guard (open question 1). Fold `registerNestingTypes` and
   `promoteSingleRecordPayloads` into the walk (the directiveless-from-field case). **Keep
   `validateUniformDomainReturnType`'s demote-to-`UnclassifiedField` intact through this slice**
   so no enforcement gap opens; it is retired in slice 5 when its replacement goes green. Gate:
   truth table + sakila pipeline `TypeSpec` + compile + execute. No dual-run flag.
5. **Field-context validation (R278 completion).** Move `DomainReturnType` onto the field and
   replace `validateUniformDomainReturnType` with a validator agreement check, *model change and
   validator rule in one commit*. Move polymorphic member admissibility (SQL-polymorphism
   requires all `TableBound`; service/reflection admits `Unbound`) to the validator and drop the
   `allowNonTableMembers` flag. Gate: truth table + sakila.
6. **Fold ConnectionPromoter into the walk.** Synthesise `Connection`/`Edge`/`PageInfo` into the
   registry on demand when an `@asConnection` field is visited. Make
   `rebuildAssembledForConnections` a *pure function of the walk's synthesised-type set* (a typed
   parameter, not a second `schema.types()` scan) so there is one producer and the rebuilt
   assembled schema cannot drift from the registry, the R165 bug class relocated to Connection
   types otherwise. Add a pipeline assertion covering the assembled-schema delta (the
   differential's blind spot). Delete the now-dead phases; keep `rejectCaseInsensitiveTypeCollisions`
   as a post-walk registry sweep (a global cross-type check, not field-driven).
7. **Prune orphans (the payoff, intended behaviour change).** Stop classifying unreachable types;
   the walk already only reaches the reachable surface. Flip slice 2's orphan *measurement* into
   an assertion that the orphan set is empty (or rejected), and update any truth-table rows that
   relied on orphan classification.

## Open design questions (defer to implementation slices)

A `principles-architect` read on this slicing landed (its findings are folded into the slice
order and the gate posture above). Remaining per-slice design forks:

1. **On-demand target classification re-entrancy and cycles.** Classifying a field's
   target inside the visit callback must guard against recursive (`A.b: B`, `B.a: A`) and
   self-referential schemas, and memoise. graphql-java avoids re-traversing nodes, but
   classify-on-demand is the caller's own recursion.
2. **`TypeRegistry` write-verb collapse.** Largely decided: the target is single
   per-name classification with idempotent re-derivation on a *compatible* repeat and an
   automatic `demote` to `UnclassifiedType` on an *incompatible* one. So `demote` survives
   as the conflict mechanism and `classify` becomes idempotent-compatible; `enrich` and
   `synthesize` should dissolve. Spec pins the exact before/after and the
   compatibility predicate (when are two directiveless-derived verdicts "compatible").
3. **Determinism of the walk.** `SchemaTraverser` order is implementation-defined.
   Directive-bearing verdicts are order-independent by construction; directiveless
   agreement and the conflict-as-error detection must be order-independent too. Decide
   between fixed-seed-order reliance and accumulate-then-compare.
4. **Input-side backing-class classification from the field definition.** The
   `JavaRecord`/`Pojo`/`JooqRecord`/`JooqTableRecord` input split is reflection-derived
   from the consuming method signature; confirm everything it needs is reachable from the
   `GraphQLFieldDefinition` read during field classification, since the walk does not
   visit arguments or input objects separately.
5. **Test ergonomics.** Unit tests call `FieldBuilder.classifyField` / `TypeBuilder`
   directly; a walk-driven classifier needs either a one-seed in-test driver or a
   direct-call shim.
6. **Reuse boundary.** `FieldBuilder.classifyQueryField` / `classifyMutationField` and the
   per-field dispatch are reusable as-is; the change is the driver, reachability, and
   on-demand target classification, not the ~5400 lines of per-field logic. Confirm the
   blast radius stays bounded to the driver.

The LSP `TypeClassification` / `FieldClassification` projections are read-only consumers
of the classified model and should be unaffected.
