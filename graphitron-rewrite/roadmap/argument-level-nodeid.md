---
id: R40
title: "Argument-level @nodeId support"
status: Backlog
bucket: validation
priority: 2
theme: nodeid
depends-on: []
---

# Argument-level @nodeId support

`@nodeId` is declared on `ARGUMENT_DEFINITION` in [`directives.graphqls`](../graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls) (line 264) but `FieldBuilder.classifyArgument` (line 754) never inspects it. The scalar-ID NodeId branch at line 815 is gated on `!list` and only fires for the synthesized case (parent table has KjerneJooqGenerator metadata); explicit `@nodeId(typeName: T)` on an argument is silently ignored, so a schema like `kompetanseregelverkGittIdV2(ider: [ID!]! @nodeId(typeName: "Kompetanseregelverk")): [Kompetanseregelverk!] @asConnection` falls through to scalar column binding at line 826 and surfaces as `column 'ider' could not be resolved in table 'kompetanseregelverk'; did you mean: ER_AKTIV, ...`.

The same-table `[ID!] @nodeId` PK-IN predicate is already wired for `@table` input-type fields via `InputField.NodeIdInFilterField` and `BodyParam.NodeIdIn` (see the "Same-table `[ID!] @nodeId` filter" entry in [`changelog.md`](changelog.md)). Lifting the same shape to top-level field arguments closes the surface gap the directive's SDL already advertises and unblocks the `kompetanseregelverkGittId`-style "lookup-by-IDs" pattern that consumer subgraphs (sis, opptak) want to express without an artificial input-wrapper type. Scope at Spec time: classifier branch in `classifyArgument` covering both list and scalar `@nodeId(typeName: T)` (same-table and FK-target variants), an `ArgumentRef` variant or `NodeIdArg` extension carrying the predicate metadata, projection into the existing `BodyParam.NodeIdIn` / `has<Qualifier>(s)` paths, and execution-tier fixtures parallel to `films_filteredBySameTableNodeId_*`.
