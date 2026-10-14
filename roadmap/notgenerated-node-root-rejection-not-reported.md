---
id: R1030
title: "A @notGenerated Query field returning Node reports no rejection of its own"
status: Backlog
bucket: bug
priority: 4
theme: diagnostics
depends-on: []
created: 2026-10-14
last-updated: 2026-10-14
---

# A @notGenerated Query field returning Node reports no rejection of its own

## Goal

A root field that carries the retired `@notGenerated` directive reports "@notGenerated is no longer supported" under its own coordinate, whatever its return type. sis reports that `Query.sisNode(id: ID!): Node @notGenerated @inaccessible` produced no error naming the field, while `sisNodeTest: String @notGenerated` in the same build was rejected as `[invalid-schema] @notGenerated is no longer supported`. The node-shaped field's rejection still voided the tenant context of every `Node` type and caused about 680 follow-on errors, so the author saw the consequences and never the cause. That cascade belongs to R1027. This item is about the missing message.

## What the code says so far

`FieldBuilder.classifyFieldInner` rejects `@notGenerated` as its first check, before the root-field split (`classifyRootField`, then `classifyQueryField`), so classification looks the same for both fields. The tenant cascade shows the field was treated as rejected.

Reported from sis on 2026-10-14 against graphitron trunk `9e6f1fa49`. Removing the directive (the field then generates as a node lookup) is the author-side fix and cleared the cascade.

## Reproduction attempt (2026-10-14): not reproduced

The defect does not reproduce on trunk `d0448c1`. No file under `graphitron/src/main`, `graphitron-model/src/main` or `graphitron-maven-plugin/src/main` changed between `9e6f1fa49` and that commit, so sis ran the same code. Scratch tests, not committed, ran the sakila fixture with `film_id` as the tenant column through `GraphQLRewriteGenerator.buildOutput()` over a captured store, which is the path `graphitron:validate`, `graphitron:generate` and `graphitron:dev` share. Every variant reported `Query.sisNode` with "@notGenerated is no longer supported" beside `Query.sisNodeTest`:

- plain SDL, with and without a tenant column (`TestSchemaHelper.buildSchema` plus `GraphitronSchemaValidator`);
- a federation `@link` importing `@inaccessible`, with `sisNode(id: ID!): Node @notGenerated @inaccessible`, with and without a tenant column;
- the field declared in `extend type Query` in a second schema file, with `Query.node(id: ID!): Node` beside it;
- the same, with a tenant-scoped child (`Film.inventory`) below a `Node` type. This reproduces sis's cascade in miniature: `Film.inventory` rejects with "no ancestor established a tenant context", and a control without `sisNode` clears it. The `sisNode` error is still reported next to the cascade.

None of the reporting surfaces drops errors either. The validator turns every `UnclassifiedField` into a `ValidationError`, `logErrors` prints the whole list, and `WatchErrorFormatter` (the renderer behind the `[invalid-schema]` label sis quoted, shared by the one-shot mojos and every `graphitron:dev` round) prints the full tree grouped by source file, then type, then field. It does not truncate, deduplicate or rank.

What is left is either a schema shape the analogue misses or a misread report. The second is plausible. The formatter files an error under the source file that declares the field, so if `sisNode` and `sisNodeTest` live in different files, the `sisNode` error sits in a different file block from its sibling and among about 680 cascade lines. Either way, the cascade that buried it is R1031's, and once R1031 lands the error is one of a handful.

**Next step before this can be specced:** get sis's raw build output, or the file layout around `Query.sisNode` (which file declares it, which directives and arguments it carries, the `schema { query: … }` root if one is declared, and any configured tags or supergraph). If the output contains `Query.sisNode`, discard this item. If it does not, the shape that differs from the variants above is the reproduction to build the Spec on.
