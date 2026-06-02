---
id: R274
title: "OutcomeType carries its success projection so the nullability invariant lives on the carrier"
status: Backlog
bucket: structural
depends-on: []
created: 2026-06-02
last-updated: 2026-06-02
---

# OutcomeType carries its success projection so the nullability invariant lives on the carrier

R244's spec frames `OutcomeType` as a carrier whose `successProjection` holds "the non-errors data
fields, all nullable (enforced)" so that "possessing an `OutcomeType` *is* the proof those invariants
hold" (the mirror-the-classifier / generation-thinking stance: the type carries what consumers rely
on). The R244 implementation does not honour that: at `FieldBuilder.resolveServiceOutcomeChannel`
(`FieldBuilder.java:~2106`) the `OutcomeType` is constructed with `successProjection = List.of()`,
and the nullable-success-projection invariant (`NonNullableSuccessProjectionField`) is instead
enforced by an inline loop over `payloadObj.getFieldDefinitions()` *before* construction. So the
invariant lives in a loop, not the type, and the `OutcomeType` value is not the proof it is
documented to be. (Surfaced by the principles-architect review of the R244 rework, finding E1; the
rework annotated the empty list rather than re-architecting, deferring the consolidation here.)

Scope when picked up: populate `OutcomeType.successProjection` with the classified non-errors data
fields at construction, move the nullability check to read off the carrier (or enforce it in the
`OutcomeType` compact constructor so the type cannot be built in a violating state), and confirm the
walker/consumers that today ignore `successProjection` either use it or have it removed. Coordinate
with R268, which also touches the `OutcomeType` / arm-switch surface. Pin with the existing
`NonNullableSuccessProjectionField` tests plus a structural assertion that a constructed
`OutcomeType` exposes its data fields.
