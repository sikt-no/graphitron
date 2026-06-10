---
id: R291
title: Strip Graphitron-internal directives and their supporting types from the published SDL
status: Spec
bucket: feature
priority: 2
depends-on: []
created: 2026-06-10
last-updated: 2026-06-10
---

# Strip Graphitron-internal directives and their supporting types from the published SDL

## Problem

The SDL that `SchemaSdlEmitter` writes to `schema.graphqls` (the artefact a consumer publishes as its subgraph) still contains Graphitron's generate-time directive definitions, their applications, and the supporting input/enum types they reference. These exist only so directive arguments type-check during classification; they are fully consumed at generate time and mean nothing to clients or to supergraph composition. Their presence currently **blocks subgraph publishing**: Apollo Studio linting flags them under `ENUM_USED_AS_INPUT_WITHOUT_SUFFIX`, `INPUT_TYPE_SUFFIX`, `TYPE_SUFFIX`, `ALL_ELEMENTS_REQUIRE_DESCRIPTION`, and `DEFINED_TYPES_ARE_UNUSED` (40+ violations observed against a real consumer schema).

`directives.graphqls` declares 8 supporting types: 5 inputs (`ErrorHandler`, `ReferencesForType`, `FieldSort`, `ExternalCodeReference`, `ReferenceElement`) and 3 enums (`ErrorHandlerType`, `SortDirection`, `MutationType`).

## Verified current state (sakila build)

The leak is asymmetric across the two published forms, verified against the built sakila example:

- **File arm** (`SchemaSdlEmitter.emit`, both federation and plain printers): contains all generator-only directive *definitions* (26 of them), their *applications* (`type Address implements Node @key(...) @node @table(name : "address")`), and all 8 supporting types.
- **Runtime arm** (generated `GraphitronSchema.build()`, served as `_service.sdl`): generator-only directive definitions and applications are already stripped (`SchemaDirectiveRegistry.isSurvivor` consumed by `DirectiveDefinitionEmitter.survivors` and `AppliedDirectiveEmitter`), and the 5 input types are already skipped at classification (`TypeBuilder.classifyType` consults `InputDirectiveInputTypes.NAMES` and returns null, so they never enter `schema.types()`). But the 3 enums are **not** skipped: they classify as `EnumType`, land in `Plan.additionalTypeNames`, and every generated `GraphitronSchema` registers `ErrorHandlerTypeType.type()`, `MutationTypeType.type()`, `SortDirectionType.type()` unconditionally, even the minimal multischema fixture that references none of them.
- **`SortDirection` is dual-use.** Consumer schemas legitimately reference it from client-facing coordinates: the orderBy contract is `input FilmOrderBy { field: FilmSort! direction: SortDirection }` (sakila fixture; the generated fetcher reads the `direction` member by name at runtime). The other 7 types have no sanctioned client-facing role.

R253 (`pipeline-runtime-sdl-parity-test`) already planned the directive half of this and analysed the print seam in detail; this item **subsumes R253** (see below) so the directive filter, the type filter, and the parity test that pins both land as one change.

## Design

### Support-type model: two tiers, one derived set

The set of Graphitron-declared support types is **derived, not hand-maintained**: a type definition belongs to the set iff it is declared in `directives.graphqls`. Two equivalent derivations exist; pick one during implementation and pin it with a test either way:

1. Parse `RewriteSchemaLoader.directivesSdl()` (the accessor already exists for drift checks) once and collect the declared type names.
2. Source attribution: `SourceLocation.getSourceName()` equals the loader's injected source name. If this route is taken, `RewriteSchemaLoader` must expose the source-name constant publicly and the classifier must read it; two string literals in two files is not acceptable for a decision that controls which types exist.

Derivation 1 is recommended: it does not depend on source-name plumbing surviving a graphql-java point release, and the one extra parse of a bundled resource is negligible. Either way the hardcoded `InputDirectiveInputTypes.NAMES` list is retired in favour of the derived set, and a unit test pins the expected 8 names so a future `directives.graphqls` edit changes the set consciously.

Within the set there are two tiers, because two different facts are being modelled:

- **Published support types** (today: exactly `SortDirection`): a client-facing reference is sanctioned. Retained (classified, registered at runtime, printed in the published SDL) iff some coordinate of a non-support type references it: field return types, argument types, input field types. No transitive closure is needed; the only support types that reference `SortDirection` are never retained themselves, so a single scan over non-support coordinates suffices.
- **Strictly internal types** (the other 7): never retained. A client-facing reference to one of them is an authoring mistake and must surface as a typed `Rejection.AuthorError` ("references graphitron-internal type X; these types exist only to shape Graphitron's build-time directive arguments"), not as today's failure mode for the 5 inputs, which is a silent classification skip that leaves a dangling `GraphQLTypeReference` in generated code and fails late and untyped at consumer schema-build time.

A uniform reachability rule for all 8 was considered and rejected: it cannot distinguish "reference is legal" from "reference is a mistake" by construction, so it would silently classify and publish `ErrorHandler` if a consumer used it as a client-facing input. That is exactly the late-failure shape the pipeline exists to eliminate.

### One decision, two consumers

The retention decision is made **once**, at classification time, and materialises as `schema.types()` membership: a support type that is not retained never enters the registry (generalising today's unconditional skip of the 5 inputs). Both downstream consumers read that one decision:

- `GraphitronSchemaClassGenerator.planFor` already builds `additionalTypeNames` from `schema.types()`; the runtime arm needs no further change.
- `SchemaSdlEmitter.emit` gains access to the `GraphitronSchema` (in scope at the call site in `GraphQLRewriteGenerator`) and drops a named type at print iff it is in the support set and absent from `schema.types()`.

This mirrors how `SchemaDirectiveRegistry.isSurvivor` is the single survivor decision for directives, consumed by both the runtime `additionalDirective(survivors)` loop and (after this item) the printer predicates. Do not recompute reachability at the print seam; that re-creates for types the two-sites-that-must-agree drift R253's Route 3 was designed to remove for directives.

### Print seam (subsumes R253 Route 3)

`SchemaSdlEmitter` adopts R253's Route 3 on both arms:

- `includeDirectiveDefinition(SchemaDirectiveRegistry::isSurvivor)` and `includeDirective(SchemaDirectiveRegistry::isSurvivor)` strip generator-only directive definitions and applications. Survivors (federation directives, `@deprecated`, consumer-declared custom directives) keep printing.
- The federation arm replaces `ServiceSDLPrinter.generateServiceSDLV2(...)` with a direct `SchemaPrinter` whose options mirror that printer's shape (R253 verified the shape via bytecode disassembly of `lambda$generateServiceSDLV2$3`), including the `includeSchemaElement` filter that drops graphql-spec built-in directive definitions.
- `includeSchemaElement` additionally drops support types not retained in `schema.types()` (both arms).

The `includeSchemaElement` predicate now does double duty (spec built-ins except the `@oneOf` carve-out, plus non-retained support types). Make it a single named, tested function, not two inlined lambdas; otherwise it becomes a fresh two-conditions-that-must-agree site.

**`@oneOf` carve-out (R283).** `@oneOf` is spec-specified, so the spec-built-in filter strips its definition, and naming it in `includeDirectiveDefinition` does not re-admit it. The existing `OneOfDirectiveSdl.augment` (file arm) and generated `withOneOfDefinition` (runtime arm) stay in place unchanged; the new predicates must treat `@oneOf` exactly as `generateServiceSDLV2` did (strip the definition, keep the application) so the augment remains correct on both arms. R253's spec carries the full analysis; it transfers verbatim.

### Descriptions on retained support types

`SortDirection` and its `ASC`/`DESC` values get SDL descriptions in `directives.graphqls`, so the retained case passes Apollo's `ALL_ELEMENTS_REQUIRE_DESCRIPTION`. Both arms derive the type from the same parse, so parity should hold for free, but the pipeline test must assert the description survives on both sides: a description that prints in the file arm but gets dropped by the runtime `EnumTypeGenerator` emission would be a parity break only that assertion catches.

### Validation

The strictly-internal-reference rejection lives where the validator can attribute it to the offending coordinate (candidate: the coordinate-walk in `GraphitronSchemaValidator`, or the field-classification site that resolves the type reference; implementer's choice, but the error must name the consumer's coordinate and the internal type, and should hint at consumer-authored alternatives). This makes the validator mirror the classifier invariant: every classify-time skip the consumer can get wrong fails at validate time.

### Relationship to R253

This item implements R253's Route 3 and re-enables `FederationBuildSmokeTest.emittedSdlMatchesRuntimeSchema` (drop `@Disabled`); the type-retention filter and the directive-survivor filter land together, pinned by the same `SchemaDiffing` assertion in the same change. Landing R291 therefore closes R253; sequencing them as two items would mean the first to land changes the shared `includeSchemaElement` seam under a still-disabled parity test. One detail to verify during implementation: the parity test's runtime side prints via `ServiceSDLPrinter.generateServiceSDLV2(runtimeSchema)` without the `withOneOfDefinition` wrapper, while the file side carries the augmented `@oneOf` definition; either the parser auto-defines `@oneOf` on both sides (making the definition a non-diff) or the test's runtime side must route through the same published form the consumer serves.

## Test plan

All SDL assertions are structural (re-parse the printed SDL, assert on the resulting type/directive sets), never substring matches on the printed string; that is the SDL analogue of the banned generated-method-body assertion.

- **Unit** (`SchemaSdlEmitterTest`): per arm, generator-only directive definitions and applications absent, survivors present; strictly-internal types absent; retained vs non-retained published support type; `@oneOf` behaviour unchanged (existing tests keep passing); `federationArmRoutesThroughServiceSdlPrinter` re-targeted to the new printer call. Unit test pinning the derived support-type set to the expected 8 names.
- **Pipeline**: support enum absent from `schema.types()` when unreferenced, present when referenced from a client-facing coordinate; retained `SortDirection` carries its description on both the classified variant and the printed SDL; strictly-internal reference from a consumer coordinate produces the typed `AuthorError`.
- **Execution / compilation** (sakila example): emitted `schema.graphqls` free of the 7 strictly-internal names and free of generator-only directive definitions/applications; main fixture retains `SortDirection` (referenced by `FilmOrderBy`/`ActorOrderBy`); re-enabled `emittedSdlMatchesRuntimeSchema` is green. The multischema fixture, which references no support type, demonstrates the all-dropped case.

Expect minor fallout in existing tests whose fixtures implicitly carried the three enums in `schema.types()` or in generated-type listings; those assertions update to the new contract.

## Files touched (minimum)

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/SchemaSdlEmitter.java`: printer options, new parameter, both arms.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/InputDirectiveInputTypes.java`: retired or rebuilt as the derived support-type set with tiers.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/TypeBuilder.java`: conditional skip replaces the unconditional input skip; enum branch gains the same gate (the support-set check must run before the enum branch).
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java` (or the classification site): strictly-internal reference rejection.
- `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`: descriptions on `SortDirection`/`ASC`/`DESC`.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphQLRewriteGenerator.java`: emit call site.
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/SchemaSdlEmitterTest.java`
- `graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/FederationBuildSmokeTest.java`: drop `@Disabled`.
- `roadmap/pipeline-runtime-sdl-parity-test.md`: deleted on landing (closed as subsumed; changelog line records both IDs).

## Out of scope

- Missing descriptions on generated client-facing relay boilerplate (`*Connection`/`*Edge` types); tracked separately as R292.
- Renaming `SortDirection` (or any retained support type) to satisfy Apollo naming-convention lints (`ENUM_USED_AS_INPUT_WITHOUT_SUFFIX`). The type is part of the public orderBy contract; renaming is a breaking change for every consumer and a lint-severity decision for the subgraph owner.
- The legacy generator and `graphitron-schema-transform` (out of AI scope; the legacy stack has its own removal mechanism in `ElementRemovalFilter`).
- Reopening the parity assertion's `SchemaDiffing` framing (R253 already settled byte-equality vs structural diff).
