---
id: R599
title: "Remove the stray @notGenerated and @experimental_constructType declarations"
status: Backlog
bucket: bug
depends-on: []
created: 2026-08-06
last-updated: 2026-08-06
---

# Remove the stray @notGenerated and @experimental_constructType declarations

`directives.graphqls` declares two directives that are not graphitron's: `@notGenerated` and
`@experimental_constructType`. The declarations are a bug with real behaviour, because
`DeclaredDirectives.names()` feeds `SchemaDirectiveRegistry.isSurvivor`: emission strips
applications of names graphitron does not own, silently swallowing them from the emitted
schema. For `@notGenerated` the pipeline additionally hard-rejects applications with a
migration message (`FieldBuilder`'s output-field arm, `BuildContext`'s input-field arm) and
lists the name in the forbidden-carrier sets. `@experimental_constructType` has no consumer
anywhere (per the census in `roadmap/audits/2026-08-06-directive-consumer-census.md`); the
former implementation plan for it is discarded with this item's filing, as its premise
(a graphitron feature awaiting an emitter) is void.

Scope when this moves to Spec:

- Remove both declarations from `directives.graphqls`. `@multitableReference` and `@record`
  are unaffected: those are graphitron's own retired/deprecated names.
- Retire `@notGenerated`'s reject sites and forbidden-carrier entries.
- `DirectiveSupportReport`: drop `experimental_constructType` from `WITHHELD_FROM_V1` (and
  its javadoc mention), decide whether `REJECTED_ON_USE` keeps a `notGenerated` mention for
  migrating consumers once the name is no longer declared; adjust
  `DirectiveSupportReportTest`, `SchemaDirectiveRegistryTest`, and
  `DirectiveDefinitionEmitterTest` fixtures that use the names.
- Docs: `docs/manual/how-to/apollo-federation.adoc` points at an
  "`@experimental_constructType` stub-from-another-subgraph pattern" in
  `federation-keys.adoc`; update both. The recovery note for the withheld reference page
  becomes moot.
- Decide the migration story: with the declarations gone, a consumer schema applying either
  name without declaring it fails assembly with an undeclared-directive `SchemaProblem`;
  a name-keyed recipe message (the `buildRecipeErrors` pattern) is the candidate replacement
  for today's located rejection, or the plain error is accepted as sufficient.
- `roadmap-tool`'s `legacy-directives.graphqls` is a historical record of the legacy surface
  and stays as-is.

The model-store spec (`roadmap/graphitron-model-captures-facts.md`) already treats both names
as foreign: no `intent_` relations exist for them, and once the declarations are gone,
consumer-declared applications land in the `applied_` fidelity families and re-emit verbatim,
so the store needs no change when this lands.

## Retired vocabulary

- `DIR_NOT_GENERATED`
- `notGenerated`
- `experimental_constructType`
