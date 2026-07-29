---
id: R558
title: "Validator mirrors for launcher-surfaced classification gaps"
status: Backlog
bucket: correctness
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-07-29
last-updated: 2026-07-29
---

# Validator mirrors for launcher-surfaced classification gaps

The root launcher migration (see `roadmap/changelog.md` when its item lands) surfaced two
classifier-side invariants with no validate-time twin, both recorded on that item's slice logs
and both cheap to close now that the launcher relation single-sources the relevant populations.
(1) The deterministic-order validation keys on `SqlGeneratingField`, which the root
`@routine`-chain leaf does not implement, so a classified list-returning routine root escapes
the rule by capability non-membership, a membership silent skip nobody recorded as a decision;
the launcher relation now carries exactly that population (`ResultShape.RecordList` with an
absent ordering over the whole covered family), so the rule can re-source off the relation
instead of the capability, or the capability can widen. (2) A `GraphitronType.TableInterfaceType`
participant without `@discriminator` classifies and renders the legacy silent shape (its
projection contributes but its rows are unroutable and its gated JOIN arms are skipped), guarded
only by one documented renderer gate in `render/DiscriminatedTableFragments`; the honest fix is
a parse-time rejection (or validator drain) so the shape fails the build instead of returning
rows the TypeResolver cannot route. Related enforcement seams to keep aligned:
`GraphitronSchemaValidator.validateJoinedTableReprojection` (the joined-table reprojection
fold's deferral drain) and the `TypeBuilder.buildParticipantList` rejection for classified
non-table members of a discriminated interface, which has no reaching SDL fixture yet (an
`@error` implementor is the nearest shape) and deserves one when this item lands.
