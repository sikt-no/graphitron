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

`FieldBuilder.classifyFieldInner` rejects `@notGenerated` as its first check, before the root-field split (`classifyRootField`, then `classifyQueryField`), so classification looks the same for both fields. The tenant cascade shows the field was treated as rejected. The likely loss is therefore downstream of classification: a node-shaped root may be handled or reported on a separate path (node dispatch, or the `Query.node` entry the tenant fold treats as a dispatch), or the rejection may be dropped or outranked when diagnostics are collected. This is unconfirmed: the first step is a pipeline-tier reproduction of the two fields side by side, checking which diagnostics come out.

Reported from sis on 2026-10-14 against graphitron trunk `9e6f1fa49`. Removing the directive (the field then generates as a node lookup) is the author-side fix and cleared the cascade.
