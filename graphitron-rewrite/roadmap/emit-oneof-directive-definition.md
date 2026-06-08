---
id: R283
title: Emit the @oneOf directive definition into generated SDL outputs
status: Spec
bucket: feature
priority: 3
depends-on: []
created: 2026-06-08
last-updated: 2026-06-08
---

# Emit the @oneOf directive definition into generated SDL outputs

When a consumer schema uses GraphQL's built-in `@oneOf` directive on an
input type, Apollo Federation composition fails at build time:

```
INVALID_GRAPHQL: [opptak] Unknown directive "@oneOf".
```

The cause is that graphitron emits the `@oneOf` *application*
(`input Foo @oneOf { ... }`) into both generated SDL outputs but never
emits the `@oneOf` *definition* (`directive @oneOf on INPUT_OBJECT`).
Both printers graphitron drives, graphql-java's `SchemaPrinter` (the
non-federation file arm) and federation-jvm's
`ServiceSDLPrinter.generateServiceSDLV2` (the federation file arm and the
runtime `_Service.sdl` value), suppress definitions of spec-defined
directives, and `@oneOf` is in graphql-java's
`DirectiveInfo.GRAPHQL_SPECIFICATION_DIRECTIVE_MAP`. graphql-java itself
serves such a schema fine because it knows `@oneOf` intrinsically; Apollo's
composer (older than the `@oneOf` spec addition) does not, so it rejects
the SDL it reads. We need the `directive @oneOf on INPUT_OBJECT`
definition present in both the on-disk `schema.graphqls` and the runtime
`{ _service { sdl } }` response.

## Where the definition is dropped

Two emission seams, each with its own printer:

1. **On-disk `schema.graphqls`** is written by
   `SchemaSdlEmitter.emit` (`graphitron/.../generators/schema/SchemaSdlEmitter.java`).
   The federation arm (`printFederationServiceSdl`) routes through
   `ServiceSDLPrinter.generateServiceSDLV2`; the non-federation arm
   (`printPlain`) uses graphql-java's `SchemaPrinter`. Neither prints the
   `@oneOf` definition.

2. **Runtime `_Service.sdl`** is baked at consumer load time inside the
   generated `GraphitronSchema.build` (federation arm emitted by
   `GraphitronSchemaClassGenerator`, lines ~240-306). `fb.build()`
   (`com.apollographql.federation.graphqljava.SchemaTransformer.build`)
   computes the SDL via `ServiceSDLPrinter.generateServiceSDLV2(...)` and
   installs it as a static data fetcher on the `_Service.sdl` field. There
   is no `setSdl` hook on `SchemaTransformer` (verified against
   federation-graphql-java-support 6.0.0: the only public surface is
   `resolveEntityType` / `fetchEntities` / `fetchEntitiesFactory` /
   `coercingForAny` / `setFederation2` / `build` plus the static
   `sdl(...)` helpers), so the runtime value can only be influenced by
   overriding the field's data fetcher after `build()`.

`@oneOf` is already a survivor name (it is not in
`SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES`), and the LSP's
`Diagnostics.SPEC_BUILTIN_DIRECTIVES` already lists it alongside `@skip`,
`@include`, `@deprecated`, `@specifiedBy`. The gap is purely that the
spec-built-in *definition* is filtered out of printed SDL, and only
`@oneOf` needs reinstating: Apollo already knows the other four.

### The filter is name-based (settles the "ride it on the schema" option)

`@oneOf` is already present in every built `GraphQLSchema`'s directive set
(`graphql.Directives.OneOfDirective`, registered as a standard directive),
so it is *not* a missing-from-the-model problem. Both printers suppress it
through `graphql.schema.idl.DirectiveInfo.isGraphqlSpecifiedDirective`,
which is a pure name lookup against `GRAPHQL_SPECIFICATION_DIRECTIVE_MAP`
(confirmed by disassembly: `map.containsKey(name)`), and `oneOf` is a key
in that map. Consequences that shape the routes below:

- Adding `@oneOf` to the schema's directive set (e.g. via the
  `additionalDirective(survivors(...))` loop at
  `GraphitronSchemaClassGenerator:230`) does **not** make either printer
  emit the definition: the strip is by name, not by presence. So there is
  no "register it on the model and let the printer do the rest" route.
- graphql-java's `SchemaPrinter` *does* honour a caller-supplied
  `includeDirectiveDefinition` predicate (it reads
  `Options.getIncludeDirectiveDefinition()`; the default is the built-in
  `ExcludeGraphQLSpecifiedDirectivesPredicate`). The non-federation arm,
  which graphitron drives directly, can therefore re-admit `@oneOf` with a
  predicate, no string editing.
- federation-jvm's `ServiceSDLPrinter.generateServiceSDLV2` builds its own
  `SchemaPrinter.Options` internally and exposes no hook, so on the seams
  that route through it (the federation file arm and the runtime
  `_Service.sdl` bake) the definition can only be reinstated by either
  swapping in a graphitron-controlled `SchemaPrinter` or augmenting the
  printer's output string.

## Detecting usage

graphql-java 25 carries the flag on the type:
`GraphQLInputObjectType.isOneOf()` returns `true` for an input object that
applies `@oneOf`. The augmentation should fire only when at least one
input object in the schema has `isOneOf()` true (so schemas that never use
`@oneOf` keep byte-identical output), and should be a no-op if the printed
SDL already contains the definition (future-proofing against a graphql-java
release that starts printing it).

## Implementation approach

The right seam differs by printer, so the design splits by arm rather than
applying one string-splice everywhere. The canonical definition to make
present is `directive @oneOf on INPUT_OBJECT` (no arguments, single
`INPUT_OBJECT` location). Every arm is gated on the schema actually
containing a oneOf input (`GraphQLInputObjectType.isOneOf()`), so schemas
that never use `@oneOf` keep byte-identical output.

There are three emission points across two seams (file, runtime), and two
of the three route through a printer graphitron does not control. A single
detection-and-augmentation helper holds the "does this schema use `@oneOf`,
and what definition string do we add" decision so the seams cannot drift:

```
boolean OneOfDirectiveSdl.usesOneOf(GraphQLSchema schema)
String  OneOfDirectiveSdl.augment(GraphQLSchema schema, String sdl)  // no-op when already defined / unused
```

**File, non-federation arm (`SchemaSdlEmitter.printPlain`).** Re-admit
`@oneOf` through the predicate graphitron already controls:
`Options.includeDirectiveDefinition(d -> SchemaDirectiveRegistry.isSurvivor(d) || d.equals("oneOf"))`
(exact predicate composed with the R253 survivor filter; the point is the
predicate names `oneOf` while still excluding `@skip`/`@include`/
`@deprecated`/`@specifiedBy`). No string editing on this arm.

**File, federation arm (`SchemaSdlEmitter.printFederationServiceSdl`).**
This routes through `generateServiceSDLV2`, whose options are not ours.
Two sub-options:

- *Preferred, if R253 has landed:* R253 Route 1/3 replaces
  `generateServiceSDLV2` with a graphitron-controlled `SchemaPrinter` whose
  `includeDirectiveDefinition` predicate graphitron owns. R283 then becomes
  the same predicate tweak as the plain arm: name `oneOf` in the predicate.
  This is the clean convergence and the reason to sequence behind R253 (see
  below).
- *Standalone fallback:* if R283 lands first, wrap the
  `generateServiceSDLV2` output in `OneOfDirectiveSdl.augment(assembled, sdl)`
  before writing. Contained, but string work on a printer output we will
  later replace; acceptable only as a bridge.

**Runtime arm (`_Service.sdl`).** This is the seam with no clean
alternative: `SchemaTransformer.build` bakes `generateServiceSDLV2(...)`
into a static fetcher and exposes no `setSdl`, and the name-based strip
means putting `@oneOf` on the schema does not help. The served value can
only be corrected by overriding the `_Service.sdl` data fetcher after
`fb.build()`. The emitted statements live in
`GraphitronSchemaClassGenerator`'s two-arg `build`, after
`federationCustomizer.accept(fb)` / `return fb.build()`, gated on
`OneOfDirectiveSdl.usesOneOf(...)` so non-oneOf schemas keep the current
codegen output verbatim. The body collapses to a single call into the
shared helper: read the already-baked SDL off the existing `_Service.sdl`
fetcher, run it through `OneOfDirectiveSdl.augment`, and reinstall a
`StaticDataFetcher` via `builtSchema.transform(b -> b.codeRegistry(...))`.

### Runtime fork: read the baked value, not a second print

The override needs the base SDL string. Prefer **reading the value the
`_Service.sdl` fetcher already holds** (one fetcher touched, no re-derive)
over re-running `generateServiceSDLV2(builtSchema)` a second time at
consumer load. The earlier draft recommended re-printing on cost grounds;
the principles read corrected this: both are cheap, and the deciding factor
is keeping the generated `build` body narrow. Re-printing duplicates the
whole SDL-derivation path to change one line and makes the emitted body
read as print/splice/transform; reading the baked value keeps it to one
helper call. Whichever path is chosen, the inline logic stays behind the
named `OneOfDirectiveSdl` method so the emitted `build` reads as a single
statement, not an expression chain.

The shared helper belongs in the generated-output runtime support surface
(reachable from both the codegen JVM, where `SchemaSdlEmitter` runs, and
the consumer JVM, where `GraphitronSchema.build` runs) rather than being
duplicated; pick the existing support package during implementation.

## Sequencing with R253

R253 (`pipeline-runtime-sdl-parity-test`) re-enables
`FederationBuildSmokeTest.emittedSdlMatchesRuntimeSchema`, which diffs the
on-disk SDL against the runtime-published SDL through graphql-java's
`SchemaDiffing` over *parsed schemas* (not strings). That is precisely the
mechanism that pins this item's cross-seam invariant: if the `@oneOf`
definition is present in both parsed schemas, the diff is empty regardless
of whitespace or placement. So R253's test, once live, *is* the parity
guard for R283, stronger than any "both seams emit the same line" prose.

The two items also overlap on `SchemaSdlEmitter`'s federation arm: R253's
Route 1/3 swaps `generateServiceSDLV2` for a graphitron-controlled
`SchemaPrinter`, which is exactly the seam R283's federation file arm wants
to own. Landing R253 first turns R283's two file arms into one uniform
predicate change and avoids string-splicing a printer output R253 then
removes. The runtime override is independent of R253 either way.

Recommendation: sequence R283 behind R253 (declare the dependency at
Spec -> Ready if the reviewer agrees), so the federation file arm converges
on the controlled-printer predicate rather than the string-splice fallback.
R283 *can* ship standalone via the fallback if R253 stalls; the runtime
override does not wait on anything.

## Test fixture

No current fixture applies `@oneOf` (confirmed: no `@oneOf` in any
`*.graphqls` under `graphitron-rewrite`). A fixture input type carrying
`@oneOf` is required:

- Unit-tier: a small schema fixture for `SchemaSdlEmitterTest` exercising
  both arms.
- Pipeline-tier: a `@oneOf` input on the sakila federated fixture (or a
  dedicated federation fixture) so `FederationBuildSmokeTest` can execute
  `{ _service { sdl } }` against generated runtime code. Adding it to the
  existing federated fixture also gives R253's parity test oneOf coverage
  for free.

## Tests

- **Unit (`SchemaSdlEmitterTest`)**: a schema with a `@oneOf` input emits a
  file containing `directive @oneOf on INPUT_OBJECT` (both federation and
  non-federation arms); a schema without `@oneOf` emits no such line
  (no-op / byte-stability guard).
- **Pipeline (`FederationBuildSmokeTest`)**: executing `{ _service { sdl } }`
  against `Graphitron.buildSchema(...)` for a fixture with a `@oneOf` input
  returns an `sdl` string containing the `@oneOf` definition, and the result
  carries no GraphQL errors.
- **Composition smoke (optional, if cheaply expressible)**: parse the
  emitted SDL through graphql-java's `SchemaParser` and assert it resolves
  `@oneOf` (i.e. the definition and application agree), the local analogue
  of the Apollo "Unknown directive" rejection.

## Files touched (anticipated)

- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/SchemaSdlEmitter.java` — `includeDirectiveDefinition` predicate on the plain arm; predicate or `augment` fallback on the federation arm (per R253 sequencing).
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/GraphitronSchemaClassGenerator.java` — emit the runtime `_Service.sdl` override in the federation `build`.
- A new `OneOfDirectiveSdl` (shared augmentation helper) in the runtime-support surface reachable from both JVMs.
- `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/SchemaSdlEmitterTest.java` — unit assertions + no-op guard.
- `graphitron-rewrite/graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/FederationBuildSmokeTest.java` — runtime `_service.sdl` assertion.
- A `@oneOf` test fixture (unit-tier schema + pipeline-tier federated fixture input).

## Out of scope

- Reinstating the other spec-built-in directive definitions (`@skip`,
  `@include`, `@deprecated`, `@specifiedBy`). Apollo already knows these;
  only `@oneOf` triggers the composition failure.
- Enforcing `@oneOf` input semantics (exactly one field set) at runtime.
  graphql-java already validates this; this item is SDL emission only.
- Re-enabling R253's parity test. That stays R253's deliverable; this item
  only commits to not breaking it.
