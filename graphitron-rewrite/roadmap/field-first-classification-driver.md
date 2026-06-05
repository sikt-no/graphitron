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
reachability-driven, field-first classification walk, then validate. The proposal
synthesises three existing threads: it is the *driver* R166
(`graphqlschemavisitor-driven-emission`) assumes, the *mechanism* R278
(`classify-polymorphic-types-in-field-context`) needs to classify polymorphic types in
field context, and a slice under the R222 (`dimensional-model-pivot`) umbrella. Scope is
the **classifier only**; emission-as-visitor (R166) is the downstream payoff this
unlocks, not part of this item.

Walk shape (converged in the originating discussion; not yet a frozen contract):

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
- *Type verdict is a pure function of the target, never the reaching field's context.*
  The only legal type directives are `@node` / `@table` / `@error`; a directive-bearing
  type classifies from its own directives, and a polymorphic type additionally reads its
  *participants'* directives (`ParticipantRef` gains the `Error` permit R278 flagged, so
  participants classify `TableBound` / `Unbound` / `Error`). Same target, same verdict,
  regardless of who points at it. Directiveless types are the one context-derived case
  (they fall out as `NestingType`, payload carrier, etc. from the reaching field); if two
  fields would classify the same directiveless type differently, that is a deterministic
  validation error, not an order-dependent resolution.
- *Population strategy leaves type classification entirely.* Whether a non-`@table`
  polymorphic member is *legal* depends on how the returning field populates the type
  (generated SQL polymorphism requires all members table-bound; service/reflection
  dispatch does not). That validity check moves to the validator in field context
  (closing R278), keeping the type verdict pure.
- *Arguments and inputs ride the field functionally.* Argument interpretation is a
  function of the field's classification; arguments and the input types they reach are
  classified as part of classifying the field, not as a separate root subsystem.
- *Classify (deterministic) then validate (sorted).* The classify->validate split stays;
  what collapses is the intra-classify multiphase. Classification must be order-
  independent; validation runs sorted for stable diagnostics.

## Open design questions (defer to Spec)

Non-trivial; warrants a Spec pass with the `principles-architect` subagent before
commitment, per R166's precedent.

1. **On-demand target classification re-entrancy and cycles.** Classifying a field's
   target inside the visit callback must guard against recursive (`A.b: B`, `B.a: A`) and
   self-referential schemas, and memoise. graphql-java avoids re-traversing nodes, but
   classify-on-demand is the caller's own recursion.
2. **`TypeRegistry` write-verb collapse.** A classify-once walk should dissolve most of
   `enrich` / `synthesize`; `demote` (a field discovering its target cannot be validly
   classified in this context) likely survives in some form. Pin the before/after.
3. **Determinism of the walk.** `SchemaTraverser` order is implementation-defined.
   Directive-bearing verdicts are order-independent by construction; directiveless
   agreement and the conflict-as-error detection must be order-independent too. Decide
   between fixed-seed-order reliance and accumulate-then-compare.
4. **Input-side backing-class classification inside the walk.** The
   `JavaRecord`/`Pojo`/`JooqRecord`/`JooqTableRecord` input split is reflection-derived
   from the consuming method signature; confirm it threads cleanly through the field-
   functional argument path.
5. **Test ergonomics.** Unit tests call `FieldBuilder.classifyField` / `TypeBuilder`
   directly; a walk-driven classifier needs either a one-seed in-test driver or a
   direct-call shim.
6. **Reuse boundary.** `FieldBuilder.classifyQueryField` / `classifyMutationField` and the
   per-field dispatch are reusable as-is; the change is the driver, reachability, and
   on-demand target classification, not the ~5400 lines of per-field logic. Confirm the
   blast radius stays bounded to the driver.

The LSP `TypeClassification` / `FieldClassification` projections are read-only consumers
of the classified model and should be unaffected.
