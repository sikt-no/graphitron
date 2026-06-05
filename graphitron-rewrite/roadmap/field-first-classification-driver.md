---
id: R279
title: "Field-first reachability-driven classification driver"
status: Backlog
bucket: architecture
priority: 4
theme: structural-refactor
depends-on: [dimensional-model-pivot]
created: 2026-06-05
last-updated: 2026-06-05
---

# Field-first reachability-driven classification driver

Backlog stub. Classification today is a strict-ordered multiphase build in
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
slice under the R222 (`dimensional-model-pivot`) umbrella and **subsumes** both R166
(`graphqlschemavisitor-driven-emission`) and R278
(`classify-polymorphic-types-in-field-context`). It is the *mechanism* R278 needs to
classify polymorphic types in field context. It supersedes R166 rather than implementing
it: R166 proposed a `GraphQLSchemaVisitor`-driven *emission* walk to fix per-emitter skip-
filter drift and the missing reachability sweep; here the visitor walk is **classification
only**, the reachability prune happens once at classification, and emission (like
validation) stays plain iteration over the already-pruned `GraphitronSchema` and needs no
per-emitter filters because the model only contains reachable, classified things. R166's
goals are met without a visitor-driven emitter.

Walk shape (converged in the originating discussion; not yet a frozen contract). The
**only** visitor-based phase is classification; validation and emission iterate the
classified `GraphitronSchema` and are not visitor-based.

- *Driver.* Seed `graphql.schema.SchemaTraverser` from the Query and Mutation roots and
  visit fields. The graphql-java visitor machinery is already in-tree
  (`ConnectionPromoter` uses `GraphQLTypeVisitorStub` + `SchemaTransformer`
  post-classification today). Other roots are reached transitively, not seeded
  separately: federation `_entities` and `@node` targets fall out of handling the
  `Query._entities` / `Query.node` field classifiers. Subscription, if added, is a third
  seed.
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

## Open design questions (defer to Spec)

Non-trivial; warrants a Spec pass with the `principles-architect` subagent before
commitment, per R166's precedent.

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
