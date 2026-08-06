# Demand and exemption census: grounding for the demand relation

A working document, not a roadmap item; it lives in `audits/` so the roadmap-tool ignores it.
It records the census of classification demand commissioned for the capture-and-derive design in
`roadmap/validation-adds-facts.md`: which reachable coordinates today never receive a
classification verdict, where each skip is decided, and what today constitutes "requires a
classification". Sibling records: `2026-08-06-directive-consumer-census.md`,
`2026-08-05-fact-base-h2-spike.md`.

## The headline: demand is negative space

There is no explicit demand predicate in production code. The requirement is exactly "every
field definition of every visited `GraphQLObjectType` whose registry verdict is not one of the
skip cases below", defined by the early returns in
`GraphitronSchemaBuilder.classifyFieldsOfObject`. Three corollaries, each verified:

- An `UnclassifiedField` error only arises from a *produced* verdict; the validator iterates the
  registry's entries (`GraphitronSchemaValidator.validateUnclassifiedField`), so a coordinate
  that never entered the registry is invisible to it.
- `SchemaReachability.reachableTypeNames` has no production consumer; the
  `reachable ⊆ classified` invariant is asserted in tests only, and at type grain, never at
  coordinate grain.
- The only structural backstop, `GraphitronSchemaBuilder.rejectDanglingTypeReferences`,
  quantifies over registered fields and checks their return types; it cannot see a missing
  coordinate, only a missing type reached from a present one.

The safety argument for the coordinate-grain holes is runtime, not build-time: an unregistered
coordinate gets graphql-java's default fetcher, indistinguishable at emit from a rejected one
except that only the rejected one fails the build.

## Exemption census

Coordinates reachable in the schema that never receive a verdict and are not errors:

| # | Population | Decision site | Predicate |
|---|---|---|---|
| E0 | every field of every interface type | `GraphitronSchemaBuilder.visitGraphQLInterfaceType` | no `classifyFieldsOfObject` call exists for interfaces; both production `classifyField` call sites pass an object type |
| F1 | all fields of a directiveless nesting target, including orphans nothing embeds | `classifyFieldsOfObject` early return | `TypeBuilder.isDirectivelessNestingTarget` |
| F2 | all fields of SDL-declared `Connection`/`Edge`/`PageInfo`/`Facets`/`FacetValue` types | same early return | registry verdict is one of the five synthesis arms |
| F3 | the DELETE carrier's single data field | `classifyFieldsOfObject` DELETE arm | carrier admits the structural DML payload scan, binding kind is DELETE, field is not errors-shaped |
| R1 | introspection types and their fields | `SchemaReachability.addUnlessIntrospection` | `name.startsWith("__")` |
| R5 | federation `_entities` / `_Entity` / `_service` | injected post-build in `GraphitronSchemaClassGenerator` | coordinates do not exist during classification |
| R6 | directive-driven synthesized connection coordinates | `ConnectionPromoter.registerSynthesised` | types join the assembled schema only post-walk |
| T1 | any type whose name starts with `_`, including author-declared ones | `TypeBuilder.classifyType` | `name.startsWith("_")` |
| T2 | graphitron's directive-argument support types | same | `DirectiveSupportTypes.isStrictlyInternal` |
| T3 | published support types not referenced from a non-support coordinate | same | `isPublished` and not retained |
| S1 | nested children of a `NestingField` | `FieldBuilder.classifyObjectReturnChildField` | classified recursively but embedded in `nestedFields()`, never registered |
| S2 | `@pivot` projection slots | `FieldBuilder` pivot arm | embedded in `PivotSpec`, never registered |
| S3 | every input-object field coordinate | `FieldRegistry.classifyInput` | trace-only; with tracing off, no artifact at all |

Facet synthesis skips (`ConnectionPromoter.facetSpecsFor`) are deliberately paired with
`rejectFacetMisuse` diagnostics, so they are not silent exemptions.

## Subscription is a demand row, not an exemption

Every `Subscription` field *is* demanded and *does* get a verdict: the `else` arm of
`FieldBuilder.classifyRootField`'s two-branch name dispatch mints `Rejection.deferred`. Under
the demand relation this is a requiring rule satisfied by a Deferred-kind violation, i.e. a
recognized capability gap, not an exemption row. One hole: the dispatch is name-keyed
(`TypeBuilder.ROOT_TYPE_NAMES`), so a schema declaring `schema { subscription: Feed }` seeds
`Feed` for reachability but classifies it as an ordinary directiveless object, and all its
fields silently take the F1 exemption instead of the deferral.

## The DELETE carrier's silent-loss window

The F3 skip is intended to be repaid by `FieldBuilder.classifyDeletePayloadField` calling
`fieldRegistry.reclassify` for the data field, but the reclassify only fires on the
`DmlElementKind.IdElement` arm. Every other arm and every earlier bail-out returns an
`UnclassifiedField` for the *mutation* field and leaves the payload's data-field coordinate with
no verdict at all; no error names it. The in-code justification is the never-traverse guarantee.

## Double classification exists today

`TypeBuilder.isNestingEdgeTarget` widens to `ResultType` targets (mixed-source nesting), so such
a target's fields are classified twice through two paths: once standalone at the type's own
visit (registry entry), once embedded in the referencing `NestingField`'s `nestedFields()`. The
coordinate-keyed claim relation collapses this structurally.

## Dead code confirmed

- `GraphitronSchemaBuilder.classifyFieldsOfObject` computes a local `skipForUnifiedPath` that
  nothing reads; the `serviceEmittedBinding` lookup feeding it is transitively dead, and the
  comment block above it describes a skip (non-DELETE and service carriers) the code no longer
  performs; those carriers fall through to the general loop.
- The `TableInterfaceType` exclusions keyed on `parentType` are unreachable at both sites
  (`FieldBuilder.classifyFieldInner`'s table-backed dispatch arm and
  `classifyChildRoutineChain`'s gate): `TableInterfaceType` is registered only under an
  interface name, and `parentType` at both sites is always looked up from an object type's
  name. The four sibling exclusions keyed on `ctx.tables.forName(...)` are live, because
  `TableIndex` indexes interface names too.
