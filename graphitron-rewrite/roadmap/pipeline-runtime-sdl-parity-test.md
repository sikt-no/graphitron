---
id: R253
title: "Make pipeline<->runtime SDL parity test pass"
status: Backlog
bucket: feature
depends-on: []
created: 2026-05-27
last-updated: 2026-05-27
---

# Make pipeline<->runtime SDL parity test pass

`FederationBuildSmokeTest.emittedSdlMatchesRuntimeSchema` is currently
`@Disabled` (landed alongside R247). The test asserts, through graphql-java's
`SchemaDiffing`, that the on-classpath `schema.graphqls` emitted by
`SchemaSdlEmitter` describes the same schema a consumer rebuilding via
`Graphitron.buildSchema(...)` sees — the strongest invariant the spec for
R247 carried for the artefact, and the one that catches both wrong-printer
dispatch and runtime/codegen drift in a single assertion.

R247 closed the federation-runtime-type half of the gap by running
`Federation.transform(assembled).setFederation2(true)...build()` inside
`SchemaSdlEmitter.printFederationServiceSdl` so `_Service`, `_entities`,
and `_Entity` line up on both sides. The remaining structural diff
(verified against the sakila federated fixture) is the non-survivor
**directive definitions and applications** — `@asConnection`, `@table`,
`@node`, `@reference`, `@service`, `@condition`, `@defaultOrder`, etc.
The runtime build strips them through
`GraphitronSchemaClassGenerator`'s `additionalDirective(survivors(assembled))`
loop and the per-type `applicationsFor` survivor filter on
`AppliedDirectiveEmitter`; the emitter still prints from `assembled`,
which carries every consumer-authored directive declaration and
application.

## Implementation routes

Both routes stay within graphitron's existing seams; pick one before
moving to Ready.

### Route 1 — filter at the print seam

Replace
`ServiceSDLPrinter.generateServiceSDLV2(federated)` in
`SchemaSdlEmitter.printFederationServiceSdl` with a direct
`SchemaPrinter` call whose options mirror `generateServiceSDLV2`'s shape
plus the survivor predicate:

[source,java]
----
var options = SchemaPrinter.Options.defaultOptions()
    .includeSchemaDefinition(true)
    .includeScalarTypes(true)
    .includeDirectiveDefinition(SchemaDirectiveRegistry::isSurvivor)
    .includeDirectives(SchemaDirectiveRegistry::isSurvivor)
    .includeSchemaElement(elt -> elt instanceof GraphQLDirective d
        ? !DirectiveInfo.isGraphqlSpecifiedDirective(d)
        : true);
return new SchemaPrinter(options).print(federated).trim();
----

The `includeSchemaElement` predicate replicates
`ServiceSDLPrinter.generateServiceSDLV2`'s spec-built-in filter (verified
via the JVM-bytecode disassembly of `lambda$generateServiceSDLV2$3`). The
non-federation arm needs the same `includeDirectiveDefinition` /
`includeDirectives` survivor filter for symmetry.

- **Pro**: entirely at the print seam, no new schema materialisation, no
  cross-module coupling.
- **Con**: the "what's a survivor" semantics live in two sites — the
  runtime build (`additionalDirective(survivors)`) and the printer
  predicate. A future divergence (e.g. a directive that's a survivor at
  runtime but the printer predicate doesn't agree) reopens the gap. The
  unit-tier `SchemaSdlEmitterTest.federationArmRoutesThroughServiceSdlPrinter`
  must switch from asserting against
  `ServiceSDLPrinter.generateServiceSDLV2(...)` to the new printer call.

### Route 2 — render from a runtime-equivalent schema

Reconstruct the runtime schema in-process at codegen, mirroring what
`GraphitronSchema.build()` does (`clearDirectives()`,
`additionalDirective(survivors)`, applied-directive filtering via
`AppliedDirectiveEmitter.applicationsFor` on every container,
`Federation.transform`), then print that single schema through
`ServiceSDLPrinter.generateServiceSDLV2`.

- **Pro**: one source of truth for the survivor semantics; the emitter
  prints exactly what the runtime would publish.
- **Con**: either evaluates just-generated Java in-process (heavy, and
  re-introduces the codegen ↔ runtime coupling we deliberately
  separated) or duplicates the runtime build logic at codegen (two
  implementations of the same survivor semantics). Both add surface
  area in a part of the codebase that's intentionally narrow.

Route 1 is the more contained change and is the recommended starting
point unless Spec discussion surfaces a directive case the printer-level
filter cannot represent.

### Route 3 — Route 1 with the survivor decision made explicit at one site

Independent-review remark surfaced during R247 self-review: Route 1's con
("splits survivor semantics across two sites") collapses if the print
predicate consumes the same
`SchemaDirectiveRegistry.isSurvivor(String)` method the codegen
`additionalDirective(survivors)` loop and per-type
`AppliedDirectiveEmitter.applicationsFor` filter already consume. Both
sides then route through one survivor decision, with the printer-level
filter as a thin lookup. The con only resurfaces if a future runtime
filter diverges from `isSurvivor` (e.g. a runtime-only override).

In practice this is what Route 1's code sketch above already does. Pull
this out as the explicit Spec framing so the Spec → Ready reviewer
doesn't re-discover it: "one survivor predicate, two consumers" is the
shape, not "two predicates that happen to agree today".

## Re-enabling the test

Either route lets the test go green by removing the `@Disabled`
annotation and its accompanying explanatory comment on
`FederationBuildSmokeTest.emittedSdlMatchesRuntimeSchema`. The test body
itself (parse both sides through
`UnExecutableSchemaGenerator.makeUnExecutableSchema`, diff via
`SchemaDiffing`) is already correct; only the input to the file side
needs to match.

## Files touched at minimum

- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/schema/SchemaSdlEmitter.java` — the printer call.
- `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/schema/SchemaSdlEmitterTest.java` — unit-tier assertion follows the new printer call.
- `graphitron-rewrite/graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/FederationBuildSmokeTest.java` — drop `@Disabled` from `emittedSdlMatchesRuntimeSchema`.

## Out of scope

- Reopening the byte-equality framing of the parity assertion. The
  current `SchemaDiffing` comparison is the right shape: it catches
  structural drift while ignoring incidental printer whitespace / sort
  order that graphql-java point releases drift on.
- Federation runtime types (`_Service`, `_entities`, `_Entity`).
  Already aligned by R247's `Federation.transform` step in
  `SchemaSdlEmitter`.
