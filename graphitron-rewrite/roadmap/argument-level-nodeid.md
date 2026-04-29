---
id: R40
title: "Argument-level @nodeId support"
status: Backlog
bucket: architecture
priority: 2
theme: nodeid
depends-on: []
---

# Argument-level @nodeId support

`@nodeId` is declared on `ARGUMENT_DEFINITION` in [`directives.graphqls`](../graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls) (line 264) but `FieldBuilder.classifyArgument` (line 754) never inspects it. The scalar-ID NodeId branch at line 815 is gated on `!list` and fires only for the synthesized case, so an explicit `@nodeId(typeName: T)` on an argument falls through to scalar column binding at line 826 and surfaces as `column 'X' could not be resolved in table 'Y'`. Reproducer from opptak: `kompetanseregelverkGittIdV2(ider: [ID!]! @nodeId(typeName: "Kompetanseregelverk")): [Kompetanseregelverk!] @asConnection`.

Scope narrows to the same-table case (arg `typeName:` resolves to the field's own backing table), reusing `InputField.NodeIdInFilterField` / `BodyParam.NodeIdIn` already wired for input-type fields (see "Same-table `[ID!] @nodeId` filter" in [`changelog.md`](changelog.md)). FK-target args inherit R20's `IdReferenceField` emission and are out of scope here. Spec should verify `@asConnection`'s field rewrite (Connection wrapping plus `first`/`after` arg synthesis) composes with the new arg classification.
