---
id: R982
title: "directiveApplications joins a recursive view, and H2 re-walks it once per application"
status: In Review
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

## Measured

On the same sis schema, with the fix installed, `graphitron:dev`'s initial run opened its ports after
321 s. That covers capture and all 28 derivation stages, and the derivation stratum alone took
137.8 s. Before the fix, the run was killed after more than ten minutes, still inside the one
`directiveApplications` statement. The store was warm (a cache from an earlier run existed), so this
run did not exercise R980's cold-store failure.

## Reviewer findings

### Round 1: In Review → Done, no verdict, 2026-09-29

A pre-read by a session that did not implement the item. It gives no verdict and hands the gate to a
fresh session. The implementing commits carry no `Claude-Session` trailer, so git log cannot show who
implemented the item. The Spec → Ready sign-off was self-signed, and the user accepted the item as it
stood.

1. It is the change the spec described. The rule view keeps the walk's body unchanged, and the table
   takes the old name, so `directiveApplications` is not edited. No other main-source reader of
   `graphql_ast_element_declaration` exists. `elementDeclarations` runs after every entry arm and
   before the only reader.
2. Replacing the rows per graph instead of stamping and sweeping them has precedent:
   `FieldColumnScopes` and `ArgumentColumnScopes` do the same. The cascading key into
   `graphql_ast_element_entry` removes the rows of an element an edit deleted, once the anchor's
   sweep runs.
3. Completeness: `StageAnswerAgreementTest` compares the table with its rule by `EXCEPT` both ways,
   and its non-vacuity case requires the table to have rows. The cost claim has no in-repo test,
   since the fixtures are too small to show it. The evidence is the sis measurement above. The next
   reviewer should decide whether that is enough for the fourth gate question.
4. Non-blocking: the `STAGES` javadoc in `StageAnswerAgreementTest` still says "the stratum's order",
   but its new first entry is filled by capture, not by a stage.
5. Verification: a full install on the tree rebased onto `4dd1dd6a8` passed every module up to
   `graphitron-sakila-example`. That module failed only `ReadmeLinkIntegrityTest`, on a roll-up the
   rebase left stale, which `e256a39` regenerates.
