---
id: R1163
title: "The principles architect carries the direction: the rulings live in the docs it reads, and every design is judged by what it lets us delete"
status: Backlog
bucket: dx
theme: tooling
depends-on: []
created: 2026-11-09
last-updated: 2026-11-09
---

# The principles architect carries the direction: the rulings live in the docs it reads, and every design is judged by what it lets us delete

## Goal

Every session working on the rewrite, on this machine, in the cloud or elsewhere, is steered by the same direction, because the principles architect (the read-only `principles-architect` subagent an author consults while drafting) carries it. Today the design rulings Alf and Filip give reach a session only when someone relays them: most live in local agent memory on one machine, so sessions elsewhere never see them, and their work drifts and is caught late, at harvest or in CI. When this lands, each ruling lives in the published architecture docs the architect reads on every invocation, stated once with why and how to apply it, in the doc it belongs to (much of the direction is already there: `code-generation-triggers.adoc` for the occurrence model, `testing.adoc` and `development-principles.adoc` for the testing discipline). The architect judges a design by where the code is going, asking what it lets us delete and what dissolves, and names the store-side shape wherever a design extends the classified model, the walk, the `intent_` family or the derivation family. Consulting it is a named step in the workflow, before a node is taken up, at a design fork and before work is handed over for harvest. The architect stays advisory and read-only; the author weighs its advice, and the verdict stays with Alf and Filip, who work with the author. A new ruling enters the docs the day it is given, through an ordinary commit to trunk.
