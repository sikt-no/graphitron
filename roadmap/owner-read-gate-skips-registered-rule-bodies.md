---
id: R941
title: "The owner-read gate resolves a declared relation through the register, so a registration cannot hide a family crossing"
status: Backlog
bucket: architecture
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-09-09
last-updated: 2026-09-09
---

# The owner-read gate resolves a declared relation through the register, so a registration cannot hide a family crossing

## Goal

A rule whose facts cross families cannot escape the ownership gate by being materialized. The fact
model decides which gatherer owns a rule by expanding it through every `intent_` relation it names
until only captured relations are left, "reading a materialized target as its rule so that a
registration cannot hide a crossing underneath it". The gate that enforces this,
`MetaDeclarationGateTest.aDeclaredViewReadsOnlyWhatItsOwnerMay`, does not do that: `viewOffenders`
joins `meta_relation_family` and filters `relation_type` to `VIEW`, so a declared relation that is a
registered target is a table and is never walked at all. Its rule lives in the `_live` source view
beside it, which is exempt from declaration because the register states it. The result is that
registering a relation removes its body from the one check that reads bodies, which is the opposite
of what the register is supposed to be, invisible to everything but cost.

Nothing is known to be misfiled today; this is an unenforced invariant rather than a live defect,
which is why it is Backlog and priority 3 rather than a bug. The shape of the fix is named by the
ownership rule itself: resolve a declared relation's definition through `meta_materialize` before
walking it, so a declared target is walked as its source view, and drop the `VIEW` filter that
currently stands in for "has a body". The gate's reach then grows with the declaration roster the
way its javadoc already claims, rather than shrinking every time a rule is registered.

Surfaced while specifying the `@nodeId` landing verdict's read cost, which needed the twenty-first
registration and found that the source views are exempt by construction. That item takes the
exemption as data (subtracting `meta_materialize.source_view_name` from the declaration domain) and
deliberately does not widen this gate, the general fix being this item's.
