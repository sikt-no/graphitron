---
id: R480
title: "@oneOf definition augment defeated by descriptions quoting the definition; federated SDL fails Apollo composition"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-07-14
last-updated: 2026-07-14
---

# @oneOf definition augment defeated by descriptions quoting the definition; federated SDL fails Apollo composition

Found 2026-07-14 by composing the generated federated fixture SDL with Apollo's composition engine (`@apollo/composition` 2.x, the same engine `rover supergraph compose` runs). Composition rejects the subgraph with `[sakila] Unknown directive "@oneOf"`, which is exactly the failure R283's augment exists to prevent.

## Root cause

`OneOfDirectiveSdl.augment` (`generators/schema/OneOfDirectiveSdl.java`) reinstates `directive @oneOf on INPUT_OBJECT` into the federation SDL after `ServiceSDLPrinter.generateServiceSDLV2` strips spec-built-in definitions. Its already-present guard is a substring check, `sdl.contains("directive @oneOf")`. The federated fixture's `Query.filmsByOneOf` field description (authored in `graphitron-sakila-example/src/main/resources/graphql/federated-schema.graphqls`) quotes the literal string `directive @oneOf on INPUT_OBJECT` while explaining the R283 mechanism, and that description prints into the emitted SDL. The substring check matches the description text, the append is skipped, and the emitted file (`target/generated-resources/graphitron/no/sikt/graphitron/generated/federated/schema.graphqls`) carries the `@oneOf` application on `FilmOneOfFilter` with no definition. The comment explaining the fix defeats the fix.

Both arms are affected identically: the codegen-side file arm (`SchemaSdlEmitter.printFederationServiceSdl` calling `OneOfDirectiveSdl.augment`) and the generated runtime arm (the `<outputPackage>.util.OneOfDirectiveSdl` mirror emitted by `OneOfDirectiveSdlGenerator`, whose logic is single-sourced from the same class), so the runtime `_Service.sdl` a router introspects is equally broken for any schema whose descriptions quote the definition. The shipped fixture hits it; a consumer schema documenting its own `@oneOf` usage can hit it the same way.

## Why no test caught it

The federation round-trip tests parse the emitted SDL with graphql-java, which knows `@oneOf` intrinsically whether or not the definition is present. Only a real Apollo composition run exposes the gap; none exists in the build today (that check is R298's work package 1, and this bug is its first scalp, found during R298 research rather than by CI).

## Fix shape

Replace the substring guard with a check that only matches an actual definition, e.g. a line-anchored match (`(?m)^directive @oneOf\b`) or, better, detect the definition on the parsed document rather than the printed string. Keep the file arm and the generated runtime arm single-sourced as today. Add a guard test whose schema has a description quoting the definition text (the shipped federated fixture already is one; asserting on its emitted SDL that a line-anchored `directive @oneOf` definition exists would have caught this). When R298's compose check lands, it covers this class of failure wholesale.
