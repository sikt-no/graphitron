---
id: R1140
title: "A path-step condition method is never checked against the source and target table pair it is called with"
status: Backlog
bucket: bug
priority: 3
theme: classification-model
depends-on: []
created: 2026-10-30
last-updated: 2026-10-30
---

# A path-step condition method is never checked against the source and target table pair it is called with

## Goal

A path-step `{condition: ...}` method is checked at classification against the table pair it will be
called with, so a missing overload is an author error at the schema coordinate, not a `javac` failure
in generated code. Today classification passes and the build fails with no schema coordinate.

Repro from kreg: `barnCondition` has nine overloads `barnCondition(VKoX parent, VKoY child)`, one per
(parent type, participant) pair of a multitable `@referenceFor` child. Remove one, say
`(VKoFag, VKoFag)`. Classification is clean; the build then fails in generated code:
`KregFagFetchers.java:[97,69] no suitable method found for barnCondition(VKoFag,VKoFag)`.

## Direction

The condition is resolved by method name plus the overload-set consistency rule, never against the
concrete (source table, target table) pair. The generated multitable stage 1 calls the method once per
(parent type, participant) pair, so the classifier already knows every pair: check each pair has a
matching overload and reject the missing one at the field coordinate naming the participant and the
pair. This also pins down that a path's source and target types together select the method. The
Spec confirms the same holds for a single-participant `@reference` path. Pipeline-tier case: a
multitable `@referenceFor` child with one overload removed is rejected at its coordinate.

Provenance: reported by the kreg Graphitron 10 port session (fs-plattform's kreg module on 10.0.0-RC46, branch `worktree-kreg-g10` at `7858137af4`, not pushed), 2026-10-30, as review comments on the specs for R1134 and R1136.
