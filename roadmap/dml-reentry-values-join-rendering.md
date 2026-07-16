---
id: R489
title: "Normalize the DML reentry correlation onto the VALUES-join primitive"
status: Backlog
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-07-16
last-updated: 2026-07-16
---

# Normalize the DML reentry correlation onto the VALUES-join primitive

R314 slice 4 named the DML reentry unit (the `rows<Name>` companion holding the projected /
discriminated mutation's follow-up SELECT, minted through the method-command registry) but kept
its correlation rendering as recorded residue: the companion keys the SELECT with the keys-IN
condition (`buildPkKeysCondition` / `buildKeysInCondition`, shared with the R451 routine-write
re-read) rather than the `VALUES(idx, key...)` join with PK self-identity that R333's re-query
unification resolution names as the one primitive. The two renderings express the same
correlation; carrying both is the same-primitive-two-spellings residue the OnlyChild precedent
covers (a shape-changing normalization that cannot land under R314's execution-tier-equivalence
acceptance without its own row-order analysis: keys-IN returns rows in table order, the
VALUES-join with `ORDER BY idx` would return write order, which is a behavior change on the bulk
arms). This item owns the normalization: render the DML companion's correlation through the same
VALUES-join primitive the batched rows methods use (scatter stays absent — it is gated on the
arrival/wrapper axis and the single-anchor DML case never fires it), settle the bulk-arm row-order
contract deliberately, and fold the routine-write step-2 re-read (`Operation.RoutineWrite` is
outside `requiresReFetch`'s produced-record set today, so it sits outside the reentry family; if
that framing changes, its re-read joins the same unit).
