---
id: R982
title: "directiveApplications joins a recursive view, and H2 re-walks it once per application"
status: In Progress
bucket: bug
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-09-29
last-updated: 2026-09-29
---

# directiveApplications joins a recursive view, and H2 re-walks it once per application

## Goal

`graphitron:dev` on a consumer-size schema gets through capture again. `1644b314b` (R876, directive
applications collapse onto the coordinate) numbers a repeated application by the merge order of the
declaration it sits inside, and reads that declaration from `graphql_ast_element_declaration`, a
`WITH RECURSIVE` view up the entry parent chain. `GraphQLAstCapture.directiveApplications` joins it.
H2 does not push the join keys into a recursive CTE: `RecursiveIndex.find` re-runs the whole walk for
every row joined to it, so the anchor costs one corpus walk per directive application. The fixtures
never notice; the consumer schema does.

## Seen on

The sis consumer schema, 2026-09-29. `graphitron:dev` sat at 100% CPU for over ten minutes without
opening the LSP or MCP ports. Two thread dumps five seconds apart both had `main` inside the one
`INSERT ... SELECT` in `directiveApplications`, under `RegularQueryExpressionIndex.find` then
`RecursiveIndex.find`. It was killed rather than waited out. The dev loop had worked the week before,
and `1644b314b` (2026-09-25 20:56) is the commit that introduced the view and its only reader.

The same shape as R817 and as the chain resolution `1d1a6117b` stored: a recursive view read once
per driving row. R980 came from the same commit but is a different fault, a failed capture in
`elements` rather than a slow one here.

## Fix

The walk becomes `graphql_ast_element_declaration_rule`, body unchanged, and
`graphql_ast_element_declaration` becomes a table under the old name, so the reader is not edited.
`GraphQLAstCapture.anchor` fills it once per reading with `elementDeclarations`, right before
`directiveApplications`: it deletes the graph's rows and inserts the rule's rows for the graph. It is
replaced rather than stamped and swept, since every row is this reading's derivation over this
reading's entries. `StageAnswerAgreementTest` holds the table to the rule by EXCEPT in both
directions, as it does for the other stored rules.
