---
id: R283
title: Emit the @oneOf directive definition into generated SDL outputs
status: In Review
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
(`input Foo @oneOf { ... }`) into the federation SDL outputs but never
emits the `@oneOf` *definition* (`directive @oneOf on INPUT_OBJECT`)
alongside it. The federation printer graphitron drives, federation-jvm's
`ServiceSDLPrinter.generateServiceSDLV2` (the federation file arm and the
runtime `_Service.sdl` value), suppresses definitions of spec-defined
directives, and `@oneOf` is in graphql-java's
`DirectiveInfo.GRAPHQL_SPECIFICATION_DIRECTIVE_MAP`. graphql-java's own
`SchemaPrinter` (the non-federation file arm) does *not* suppress them, so
it is not part of the bug (see "Which seam actually drops it" below).
graphql-java itself serves such a schema fine because it knows `@oneOf`
intrinsically; Apollo's composer (older than the `@oneOf` spec addition)
does not, so it rejects the subgraph SDL it reads during composition. We
need the `directive @oneOf on INPUT_OBJECT` definition present in both the
on-disk federation `schema.graphqls` and the runtime `{ _service { sdl } }`
response.

## Which seam actually drops it

The definition is dropped on exactly one printer, `generateServiceSDLV2`,
which graphitron drives from two call sites:

1. **On-disk federation `schema.graphqls`** is written by
   `SchemaSdlEmitter.emit` (`graphitron/.../generators/schema/SchemaSdlEmitter.java`).
   The federation arm (`printFederationServiceSdl`) routes through
   `ServiceSDLPrinter.generateServiceSDLV2`, which strips the `@oneOf`
   definition. The non-federation arm (`printPlain`) uses graphql-java's
   `SchemaPrinter`, which does **not** strip it (verified below), and so
   needs no change.

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

Both call sites use the same printer, so both take the same fix; the plain
arm is not involved.

`@oneOf` is already a survivor name (it is not in
`SchemaDirectiveRegistry.GENERATOR_ONLY_DIRECTIVES`), and the LSP's
`Diagnostics.SPEC_BUILTIN_DIRECTIVES` already lists it alongside `@skip`,
`@include`, `@deprecated`, `@specifiedBy`. The gap is purely that the
federation printer filters the spec-built-in *definition* out of printed
SDL, and only `@oneOf` needs reinstating there: Apollo already knows the
other four.

### What each printer does with `@oneOf` (verified against the pinned versions)

`@oneOf` is already present in every built `GraphQLSchema`'s directive set
(`graphql.Directives.OneOfDirective`, registered as a standard directive),
so it is *not* a missing-from-the-model problem. The two printers diverge,
confirmed by running graphql-java 25.0 and federation-jvm 6.0.0 against a
minimal `input Filter @oneOf` schema:

- graphql-java's `SchemaPrinter` (the **plain arm**) prints **all**
  directive definitions by default, `@oneOf` included. Its default
  `includeDirectiveDefinition` predicate returns `true` for every name
  (`test("oneOf")`, `test("skip")`, and even `test("myCustom")` all return
  `true`); it is *not* an exclude-spec-built-ins predicate. Running
  `printPlain`'s exact option chain emits `directive @oneOf on INPUT_OBJECT`
  verbatim. **So the non-federation arm has no bug and needs no change.**
- federation-jvm's `ServiceSDLPrinter.generateServiceSDLV2` (the
  **federation arm**, used by both the federation file output and the
  runtime `_Service.sdl` bake) emits the `@oneOf` *application*
  (`input Filter @oneOf`) but drops the *definition*. It builds its own
  `SchemaPrinter.Options` internally, filtering spec-specified directives
  (`DirectiveInfo.isGraphqlSpecifiedDirective`, a name lookup against
  `GRAPHQL_SPECIFICATION_DIRECTIVE_MAP` in which `oneOf` is a key), and
  exposes no hook. So on the two seams that route through it, the
  definition can only be reinstated by augmenting the printer's output
  string or swapping in a graphitron-controlled `SchemaPrinter`.

Adding `@oneOf` to the schema's directive set (e.g. via the
`additionalDirective(survivors(...))` loop at
`GraphitronSchemaClassGenerator:230`) does **not** make the federation
printer emit the definition: its strip is by name, not by presence. So
there is no "register it on the model and let the printer do the rest"
route for the federation arm.

## Detecting usage

graphql-java 25 carries the flag on the type:
`GraphQLInputObjectType.isOneOf()` returns `true` for an input object that
applies `@oneOf`. The augmentation should fire only when at least one
input object in the schema has `isOneOf()` true (so schemas that never use
`@oneOf` keep byte-identical output), and should be a no-op if the printed
SDL already contains the definition (future-proofing against a graphql-java
release that starts printing it).

## Implementation approach

The bug is confined to one printer (`generateServiceSDLV2`) feeding two
call sites: the federation file arm (codegen time, in `SchemaSdlEmitter`)
and the runtime `_Service.sdl` bake (consumer load time, in the generated
`GraphitronSchema.build`). The plain arm is untouched. The canonical
definition to make present is `directive @oneOf on INPUT_OBJECT` (no
arguments, single `INPUT_OBJECT` location). Both arms are gated on the
schema actually containing a oneOf input (`GraphQLInputObjectType.isOneOf()`,
present in graphql-java 25), so schemas that never use `@oneOf` keep
byte-identical output.

The two arms run in different JVMs, so they cannot share one compiled
runtime class. The consumer compiles the generated `GraphitronSchema`
against graphql-java + federation-jvm only; the `graphitron` codegen module
is deliberately off the consumer classpath (test-scoped in the example, and
`GenerateMojo` emits to `GENERATE_SOURCES`, so the federated build is
main-compiled without it). The no-drift guarantee therefore comes from a
single **codegen-side** source of truth that both arms consume, mirroring
how `ConnectionHelper` and the other runtime helpers are emitted into the
consumer tree rather than shipped from `graphitron`.

That source of truth is a codegen-side `OneOfDirectiveSdl` in
`no.sikt.graphitron.rewrite.generators.schema`:

```
String  OneOfDirectiveSdl.DEFINITION = "directive @oneOf on INPUT_OBJECT"
boolean OneOfDirectiveSdl.usesOneOf(GraphQLSchema schema)
String  OneOfDirectiveSdl.augment(GraphQLSchema schema, String sdl)  // no-op when unused / already present
```

The file arm calls it directly. The runtime arm is served by a small helper
class **generated into `<outputPackage>.util`** (the `ConnectionHelper`
precedent) whose definition literal is emitted from `DEFINITION`, so the one
thing that could semantically drift, the exact definition string, lives in a
single Java constant.

**File, federation arm (`SchemaSdlEmitter.printFederationServiceSdl`).**
Wrap the `generateServiceSDLV2` output in
`OneOfDirectiveSdl.augment(assembled, sdl)` before writing. `augment` is a
no-op unless `usesOneOf` and the definition is not already present, so
non-oneOf schemas keep byte-identical output.

**Runtime arm (`_Service.sdl`).** `SchemaTransformer.build` bakes
`generateServiceSDLV2(...)` into a static fetcher and exposes no `setSdl`,
so the served value can only be corrected by overriding the `_Service.sdl`
data fetcher after `fb.build()`. `GraphitronSchemaClassGenerator` emits this
as a one-line tail change in the two-arg `build`, gated at codegen time on
`OneOfDirectiveSdl.usesOneOf(assembled)`: when the schema uses `@oneOf`, the
final `return fb.build()` becomes
`return <outputPackage>.util.OneOfDirectiveSdl.withOneOfDefinition(fb.build())`;
otherwise the current `return fb.build()` is emitted verbatim. Folding the
override into the returned expression sidesteps the dead end of emitting
statements after a `return`. The generated `withOneOfDefinition(GraphQLSchema)`
helper does the work in legible statement form: return the schema unchanged
unless an input object reports `isOneOf()`; otherwise re-print the served SDL
via `ServiceSDLPrinter.generateServiceSDLV2(schema)`, append `DEFINITION` if
absent, reinstall a `StaticDataFetcher` carrying the augmented string on
`_Service.sdl` through `schema.transform(b -> b.codeRegistry(...))`, and
return the transformed schema. `ServiceSDLPrinter` and `StaticDataFetcher`
are federation-jvm / graphql-java types already on the consumer's compile
classpath, so the generated helper needs no `graphitron` dependency.

Both arms append the same `DEFINITION` to the same `generateServiceSDLV2`
output, which is what keeps the on-disk federation SDL and the runtime
`_Service.sdl` in lockstep (the invariant R253's parity test pins; see below).

### Why not a controlled `SchemaPrinter` instead of string augmentation

`generateServiceSDLV2` exposes no options hook, so the alternative to
augmenting its output is to stop calling it and drive a
graphitron-controlled `SchemaPrinter` whose predicates re-admit `@oneOf`.
That is a larger change than this item warrants on its own, and it is
exactly the seam R253 reshapes (see below). Until R253 lands, string
augmentation of the `generateServiceSDLV2` output is the contained fix, and
the helper boundary makes it easy to retarget later.

### Runtime base string: re-print inside the generated helper

The runtime override needs the base SDL string. With the override living in
the generated `withOneOfDefinition` helper rather than inline in `build`, the
"keep the emitted `build` body narrow" concern an earlier draft weighed is
already satisfied: `build`'s change is one returned call either way. Inside
the helper, re-printing via `ServiceSDLPrinter.generateServiceSDLV2(schema)`
is the cleaner base-string source than reading the value off the existing
`_Service.sdl` fetcher: it is the exact call `FederationBuildSmokeTest`
already uses to read the runtime-published SDL, and it avoids depending on
`StaticDataFetcher` exposing its baked value (it does not; the value is only
reachable by invoking the fetcher). The helper body stays explicit statement
form per the emitted-code conventions, named locals, no expression chain.

### Helper placement: codegen-side source, generated runtime helper

A single compiled `OneOfDirectiveSdl` reachable from both JVMs is not
available: the file arm runs in `graphitron`, and the runtime arm compiles on
the consumer classpath, which excludes the `graphitron` codegen module by
design. The placement splits along that boundary, anchored on one constant:

- **Codegen-side** `no.sikt.graphitron.rewrite.generators.schema.OneOfDirectiveSdl`
  holds `DEFINITION`, `usesOneOf`, and `augment`; the file arm calls it
  directly.
- **Generated** `<outputPackage>.util.OneOfDirectiveSdl` is emitted by a new
  `OneOfDirectiveSdlGenerator` (in `generators/util`, beside
  `ConnectionHelperClassGenerator`), wired into `GraphQLRewriteGenerator`'s
  per-run `write(..., "util", ...)` under a `federationLink && usesOneOf(assembled)`
  gate. The `federationLink` conjunct matters: the generated helper's only caller
  is the runtime federation `build` arm (the wrapped `return`, itself inside
  `if (federationLink)`), so a non-federation schema that uses `@oneOf` needs no
  runtime helper (its file arm goes through `printPlain`, which already emits the
  definition, and its runtime `build` has no `_Service.sdl` to correct). Gating on
  `usesOneOf` alone would emit a dead, uncalled helper into a non-federation
  consumer's `util` package. Both `federationLink` and `assembled` are in scope at
  the `write(...)` site in `runPipeline`, so the conjunction is a one-line guard.
  Its `DEFINITION` literal is emitted from the codegen-side constant, so the exact
  definition string is single-sourced; the writer's orphan sweep removes the
  generated helper if a schema later drops `@oneOf` (or drops federation).

This is the same codegen/runtime division the rest of the runtime support
surface already uses (`ConnectionHelper`, the generated `ConstraintViolations`
helper): canonical knowledge lives codegen-side, the runtime body is emitted
into the consumer tree, and no consumer takes a runtime dependency on the
generator.

## Relationship to R253 (no dependency, mutual non-regression)

R253 (`pipeline-runtime-sdl-parity-test`) re-enables
`FederationBuildSmokeTest.emittedSdlMatchesRuntimeSchema`, which diffs the
on-disk SDL against the runtime-published SDL through graphql-java's
`SchemaDiffing` over *parsed schemas* (not strings). That is precisely the
mechanism that pins this item's cross-seam invariant: if the `@oneOf`
definition is present in both parsed schemas, the diff is empty regardless
of whitespace or placement. So R253's test, once live, *is* the parity
guard for R283, stronger than any "both seams emit the same line" prose.
That is the one durable tie between the items.

The two items also touch `SchemaSdlEmitter`'s federation arm, but **not**
in the convergent way an earlier draft assumed. R253's Route 1/3 swaps
`generateServiceSDLV2` for a graphitron-controlled `SchemaPrinter` carrying
`includeSchemaElement(elt -> !isGraphqlSpecifiedDirective(directive))`,
which strips `@oneOf` (it *is* spec-specified). Naming `oneOf` only in that
printer's `includeDirectiveDefinition` predicate does **not** re-admit it:
the `includeSchemaElement` filter removes it again (verified: the printed
SDL contains no `directive @oneOf`). So there is no "one predicate tweak"
convergence, and no reason to block R283 behind a still-`Backlog` R253.

`depends-on` stays empty. R283 ships standalone via `augment` on the
`generateServiceSDLV2` output. The real coupling runs the other way:
**whichever of the two lands second must preserve the `@oneOf` carve-out.**
If R253 lands after R283, its controlled printer must carve `oneOf` out of
*both* `includeSchemaElement` and `includeDirectiveDefinition` (or keep an
`augment`-equivalent), or it re-introduces this bug; R253's now-live parity
test plus the oneOf fixture below will catch the regression. A
cross-reference note belongs in both plans.

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
- Pipeline-tier (non-federation): a `@oneOf` input on a non-federation
  fixture (existing or dedicated) to exercise the `federationLink=false,
  usesOneOf=true` branch, which the gate guard above pins by asserting no
  runtime helper is emitted there.

## Tests

- **Unit (`SchemaSdlEmitterTest`)**: a schema with a `@oneOf` input emits a
  **federation-arm** file containing `directive @oneOf on INPUT_OBJECT`
  (this is the new behavior); a schema without `@oneOf` emits no such line
  on the federation arm (no-op / byte-stability guard). A plain-arm
  assertion that the definition is present is a regression guard on existing
  graphql-java 25.0 behavior, not coverage of new code; include it if cheap,
  but label it as such so a future reader does not mistake it for the fix.
- **Pipeline (`FederationBuildSmokeTest`)**: executing `{ _service { sdl } }`
  against `Graphitron.buildSchema(...)` for a fixture with a `@oneOf` input
  returns an `sdl` string containing the `@oneOf` definition, and the result
  carries no GraphQL errors.
- **Pipeline (gate guard, non-federation `@oneOf`)**: the `federationLink`
  conjunct on the runtime-helper gate is otherwise untested; the
  federation-only cases above would still pass if it were dropped. A
  non-federation schema that uses `@oneOf` (a `@oneOf` input on a
  non-federation pipeline fixture, or a small dedicated one) must emit
  **no** `<outputPackage>.util.OneOfDirectiveSdl.java`, the dead-helper case
  the gate prevents, while its on-disk plain SDL still carries the `@oneOf`
  definition (`printPlain` already emits it). This pins the fix the
  Spec-review round that added `federationLink && usesOneOf` introduced, a
  hole the spec itself flags above as one the federation-only plan would not
  catch.
- **Composition smoke (optional, if cheaply expressible)**: parse the
  emitted federation SDL through graphql-java's `SchemaParser` and assert it
  resolves `@oneOf` (i.e. the definition and application agree), the local
  analogue of the Apollo "Unknown directive" rejection.

## Files touched (anticipated)

- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/SchemaSdlEmitter.java`: `OneOfDirectiveSdl.augment(...)` over the `generateServiceSDLV2` output on the federation arm. The plain arm (`printPlain`) is not touched; it already emits the definition.
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/GraphitronSchemaClassGenerator.java`: one-line tail change in the two-arg federation `build`: `return <outputPackage>.util.OneOfDirectiveSdl.withOneOfDefinition(fb.build())` under the `usesOneOf` gate, else `return fb.build()` verbatim.
- A new codegen-side `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/OneOfDirectiveSdl.java` (source of truth: `DEFINITION`, `usesOneOf`, `augment`), called directly by the file arm.
- A new `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/util/OneOfDirectiveSdlGenerator.java` emitting the consumer-side `<outputPackage>.util.OneOfDirectiveSdl` runtime helper (`withOneOfDefinition`), plus its `federationLink && usesOneOf`-gated wiring into `GraphQLRewriteGenerator` (the helper serves only the federation runtime arm). The generated helper has no checked-in source file.
- `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/SchemaSdlEmitterTest.java`: unit assertions + no-op guard.
- `graphitron-rewrite/graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/FederationBuildSmokeTest.java`: runtime `_service.sdl` assertion.
- A `@oneOf` test fixture (unit-tier schema + pipeline-tier federated fixture input).

## Out of scope

- Reinstating the other spec-built-in directive definitions (`@skip`,
  `@include`, `@deprecated`, `@specifiedBy`). Apollo already knows these;
  only `@oneOf` triggers the composition failure.
- Enforcing `@oneOf` input semantics (exactly one field set) at runtime.
  graphql-java already validates this; this item is SDL emission only.
- Re-enabling R253's parity test. That stays R253's deliverable; this item
  only commits to not breaking it.
