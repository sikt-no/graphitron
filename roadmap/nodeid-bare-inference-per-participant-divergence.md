---
id: R726
title: "Bare @nodeId inference on a multitable filter can answer differently per participant with no diagnostic"
status: Backlog
bucket: bug
priority: 4
theme: nodeid
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Bare @nodeId inference on a multitable filter can answer differently per participant with no diagnostic

A `@nodeId` without `typeName:` on a filter input field or argument infers its node type from the containing table (`NodeIdLeafResolver.inferTypeName`, backed by `ctx.nodes.forTable`). On a query returning a multitable interface, classification re-runs once per participant with that participant's table, so the inference re-runs too: each branch can infer a different node type, or one branch can reject as ambiguous while its siblings resolve, and nothing tells the author the leaf means different things on different branches. The decoded filter then compares against differently-typed keys per branch, silently. The likely shape of the fix is a consistency check at the consuming field (all participants must infer the same node type, otherwise demand an explicit `typeName:`), which matches how `inferTypeName` already prefers rejection with a "specify explicitly" message over guessing at a single coordinate. Surfaced while speccing the per-participant join-path item for the same coordinate; the participant-identity threading that item builds gives this check its natural seam.
