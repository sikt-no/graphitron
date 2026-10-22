---
id: R274
title: "OutcomeType carries its success projection so the error-arm invariant lives on the carrier"
status: Backlog
theme: error-channel
bucket: structural
depends-on: []
created: 2026-06-02
last-updated: 2026-10-22
---

# OutcomeType carries its success projection so the error-arm invariant lives on the carrier

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

The invariant itself has since changed shape. "All nullable" is now "every data field has an
`ErrorArmAnswer` other than `NONE`": a nullable data field answers `null` on the error arm and a
non-null plain list answers `[]`; only a non-null single or connection is rejected. The check is
`FieldBuilder.nonNullableSuccessProjection`, which derives the answer with `ErrorArmAnswer.of` over
`BuildContext.buildWrapper`, the same derivation every classified leaf's `ReturnTypeRef.wrapper()`
comes from. Moved onto the carrier, it reads the classified leaves' wrappers rather than the SDL.

Scope when picked up: populate `OutcomeType.successProjection` with the classified non-errors data
fields at construction, move the error-arm check to read off the carrier's leaves (or enforce it in
the `OutcomeType` compact constructor so the type cannot be built in a violating state), and confirm the
walker/consumers that today ignore `successProjection` either use it or have it removed. Coordinate
with R268, which also touches the `OutcomeType` / arm-switch surface. Pin with the existing
`NonNullableSuccessProjectionField` tests plus a structural assertion that a constructed
`OutcomeType` exposes its data fields.
