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
call sites: the federation file arm and the runtime `_Service.sdl` bake.
Both take the same fix, so the design is one augmentation helper invoked at
two sites, not a per-arm split. The plain arm is untouched. The canonical
definition to make present is `directive @oneOf on INPUT_OBJECT` (no
arguments, single `INPUT_OBJECT` location). Both sites are gated on the
schema actually containing a oneOf input (`GraphQLInputObjectType.isOneOf()`),
so schemas that never use `@oneOf` keep byte-identical output.

A single detection-and-augmentation helper holds the "does this schema use
`@oneOf`, and what definition string do we add" decision so the two sites
cannot drift:

```
boolean OneOfDirectiveSdl.usesOneOf(GraphQLSchema schema)
String  OneOfDirectiveSdl.augment(GraphQLSchema schema, String sdl)  // no-op when already defined / unused
```

**File, federation arm (`SchemaSdlEmitter.printFederationServiceSdl`).**
Wrap the `generateServiceSDLV2` output in
`OneOfDirectiveSdl.augment(assembled, sdl)` before writing. `augment` is a
no-op unless `usesOneOf` and the definition is not already present, so
non-oneOf schemas keep byte-identical output.

**Runtime arm (`_Service.sdl`).** `SchemaTransformer.build` bakes
`generateServiceSDLV2(...)` into a static fetcher and exposes no `setSdl`,
so the served value can only be corrected by overriding the `_Service.sdl`
data fetcher after `fb.build()`. The emitted statements live in
`GraphitronSchemaClassGenerator`'s two-arg `build`, after
`federationCustomizer.accept(fb)` / `return fb.build()`, gated on
`OneOfDirectiveSdl.usesOneOf(...)` so non-oneOf schemas keep the current
codegen output verbatim. The body collapses to a single call into the
shared helper: read the already-baked SDL off the existing `_Service.sdl`
fetcher, run it through `OneOfDirectiveSdl.augment`, and reinstall a
`StaticDataFetcher` via `builtSchema.transform(b -> b.codeRegistry(...))`.

Both sites pass the same SDL string through the same `augment` call, which
is what keeps the on-disk federation SDL and the runtime `_Service.sdl` in
lockstep (the invariant R253's parity test pins; see below).

### Why not a controlled `SchemaPrinter` instead of string augmentation

`generateServiceSDLV2` exposes no options hook, so the alternative to
augmenting its output is to stop calling it and drive a
graphitron-controlled `SchemaPrinter` whose predicates re-admit `@oneOf`.
That is a larger change than this item warrants on its own, and it is
exactly the seam R253 reshapes (see below). Until R253 lands, string
augmentation of the `generateServiceSDLV2` output is the contained fix, and
the helper boundary makes it easy to retarget later.

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
- **Composition smoke (optional, if cheaply expressible)**: parse the
  emitted federation SDL through graphql-java's `SchemaParser` and assert it
  resolves `@oneOf` (i.e. the definition and application agree), the local
  analogue of the Apollo "Unknown directive" rejection.

## Files touched (anticipated)

- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/SchemaSdlEmitter.java` — `OneOfDirectiveSdl.augment(...)` over the `generateServiceSDLV2` output on the federation arm. The plain arm (`printPlain`) is not touched; it already emits the definition.
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

## Spec review round 2: open blocker before Ready

One material finding; the rest of the plan is sound and unusually well
grounded (every cited graphitron symbol and line number verified, and the
R253-vs-R247 attribution is correct: R247 deferred
`emittedSdlMatchesRuntimeSchema`, R253 is the dedicated re-enable item).

**The shared helper cannot be a single class reachable from both JVMs.**
The "Implementation approach" section places `OneOfDirectiveSdl` in "the
generated-output runtime support surface (reachable from both the codegen
JVM ... and the consumer JVM ...) ... pick the existing support package
during implementation." That dual-reachable surface does not exist in the
current module layout, so the placement is an unresolved design fork, not a
deferrable package choice. Evidence:

- The file arm (`SchemaSdlEmitter`) runs in the `graphitron` codegen module.
- The runtime arm (federated `GraphitronSchema.build`) is generated to
  `target/generated-sources` and **main-compiled** in `graphitron-sakila-example`
  (`GenerateMojo` defaults to `GENERATE_SOURCES`; pom execution
  `rewrite-generate-federated`). Its compile-scope deps are graphql-java +
  federation-jvm + jakarta-validation only; the `graphitron` module is
  **test**-scoped there, and the pom comment that enumerates the federated
  generated code's compile needs lists Federation / graphql-java, not
  `graphitron`.
- The runtime-helper precedent (`ConnectionHelper`) is **generated into the
  consumer's `outputPackage + ".util"`** (`GraphQLRewriteGenerator` write,
  referenced via `ClassName.get(outputPackage + ".util", ...)`), not shipped
  from `graphitron`. No generator emits a `no.sikt.graphitron.rewrite.*`
  class reference into consumer code.

So a single compiled `OneOfDirectiveSdl` referenced by the generated
`GraphitronSchema.build` would fail the example's main compile (the very
`-Plocal-db` compile tier this plan relies on), unless federation consumers
are made to depend on the whole codegen module at runtime, which contradicts
the test-scope design and the "contained fix" framing.

Recommended resolution (please pick one explicitly before requesting Ready;
redirect if you have context I'm missing):

- **Generate the runtime helper; share the codegen-side source of truth.**
  Keep one helper in `graphitron` holding the definition string and
  `usesOneOf`. The file arm calls it directly. The runtime arm is served by
  an `OneOfDirectiveSdl` *generated into `<outputPackage>.util`* (the
  ConnectionHelper pattern) whose body the codegen helper emits. Both call
  paths execute in the codegen JVM, so "the two sites cannot drift" is
  preserved by the single codegen-side source, with no consumer-to-codegen
  coupling. The runtime helper keeps the `String augment(GraphQLSchema, String)`
  shape; only its home moves.
- **Introduce a consumer-facing runtime module** (or make `graphitron` a
  runtime dep of federation consumers). Larger blast radius; choose only if
  the generated-helper route cannot carry it.

Whichever is chosen, rewrite the helper-signature block and the "shared
helper belongs in ..." paragraph to name the home and stop describing one
class on both classpaths. The two-tier tests already pin each side
independently (unit = file arm contains the definition; pipeline
`_service.sdl` = runtime arm contains it), so the fix stays fully testable
once placement is settled.

Minor, fold in while revising:

- The runtime-arm paragraph says the override lands "after
  `federationCustomizer.accept(fb)` / `return fb.build()`." Nothing can be
  emitted after a `return`; the current `return fb.build()`
  (`GraphitronSchemaClassGenerator:291`) has to become
  capture-transform-return. The follow-on sentence already implies this;
  phrase it as replacing the bare return.
- "Read the baked SDL off the existing `_Service.sdl` fetcher" assumes the
  fetcher exposes its value; `StaticDataFetcher` has no value getter (you
  invoke `get(env)`). The re-print fallback the spec already documents
  de-risks this, so keep the recommendation, just do not assume a getter.
