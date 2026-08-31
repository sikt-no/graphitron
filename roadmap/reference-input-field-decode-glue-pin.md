---
id: R894
title: "Emitter-tier pin: the @reference-carrying input-field @nodeId form emits its decode in the rendered glue"
status: Backlog
bucket: testing
theme: nodeid
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# Emitter-tier pin: the @reference-carrying input-field @nodeId form emits its decode in the rendered glue

The coordinate from [issue 536](https://github.com/sikt-no/graphitron/issues/536), a list-typed filter input field carrying both `@nodeId(typeName:)` and a multi-hop `@reference(path:)`, is covered at two tiers but not the one between them. The model tier pins that classification plans the decode (`NodeIdPipelineTest.junctionChain_inputField_bindsRemotelyOverTwoHops` asserts the carrier's `NodeIdDecodeKeys` leaf), and the execution tier runs encoded ids end-to-end against PostgreSQL (`TranslatedFkTargetFilterExecutionTest.junctionChain_inputFieldForm_returnsTheSameRows`). No test renders the condition glue for this form and asserts the decode call is in it: the emitter-tier decode pins that exist (`ConditionGluePipelineTest`, `NodeIdReferenceFilterPipelineTest`) all cover same-table input fields without `@reference`, or direct query-field arguments.

The gap matters because the model carrying a decode and the glue emitting one can diverge. `ConditionGlueRenderer.nestedExtraction` ends in a fall-through that casts the raw wire traversal to the binding's local type; for a decode-typed binding whose leaf the decode arm fails to recognise, that is a compile-clean cast of base64 strings to the decoded list, the per-request `ClassCastException` the issue describes. On the current tree the fall-through is unreachable for this coordinate (the decode arm and `decodeLeafOf` test the same leaf), but nothing pins that, and the execution tier would catch a regression only for the fixture shapes it runs and at much higher cost. The work is a pipeline-tier test in the mould of `NodeIdReferenceFilterPipelineTest`: build the two-hop `@reference` input-field SDL, render the committed conditions, and assert the glue class carries the `decode<Type>KeysOrThrow` helper and the coordinate's condition method calls it, at both arities if the fixture reaches a composite key. Sibling to the membership guard item (R893): the guard protects the whole instruction class at build time in consumer builds, this pin protects the one known-good coordinate in this reactor's own suite.
