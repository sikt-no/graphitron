---
id: R558
title: "Validator mirrors for launcher-surfaced classification gaps"
status: Backlog
bucket: correctness
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-07-29
last-updated: 2026-07-30
---

# Validator mirrors for launcher-surfaced classification gaps

The root launcher migration (see `roadmap/changelog.md` when its item lands) surfaced two
classifier-side invariants with no validate-time twin, both recorded on that item's slice logs
and both cheap to close now that the launcher relation single-sources the relevant populations.
(1) is closed: the deterministic-order rule used to skip the root `@routine` chain, first by
capability non-membership and later by an explicit carve-out on the source axis, and
`roadmap/routine-composition-surface-from-facts.md` removed the carve-out rather than
re-sourcing the rule off the launcher relation. A routine-backed list now fails the build
unless it names an order, with a message forked on terminus kind. (2) A
`GraphitronType.TableInterfaceType`
participant without `@discriminator` classifies and renders the legacy silent shape (its
projection contributes but its rows are unroutable and its gated JOIN arms are skipped), guarded
only by one documented renderer gate in `render/DiscriminatedTableFragments`; the honest fix is
a parse-time rejection (or validator drain) so the shape fails the build instead of returning
rows the TypeResolver cannot route. Related enforcement seams to keep aligned:
`GraphitronSchemaValidator.validateJoinedTableReprojection` (the joined-table reprojection
fold's deferral drain) and the `TypeBuilder.buildParticipantList` rejection for classified
non-table members of a discriminated interface, which has no reaching SDL fixture yet (an
`@error` implementor is the nearest shape) and deserves one when this item lands.

(3) Deferred here from the command programme's DML slice per its design record: the
declared-but-unpopulated `Operation` arms (`UpdateMatching`, `DeleteMatching`, `EntityResolve`,
`Count`, `Facet`) owe a per-arm answer to "does a schema reaching the arm get a validate-time
rejection" (the validator-mirror rule). The matching pair's current state: no classifier path
constructs either arm (condition-matched UPDATE / DELETE are unimplemented behaviour, category
(a) in the exemption-list triage), so no schema reaches them and no rejection can fire; the
per-arm answer to record when this item lands is either a located rejection at the SDL surface
that would classify onto them, or a statement that the surface itself is unparseable today so
unreachability is structural. `Count` / `Facet` are synthesised (no SDL origin; the connection
promoter mints them), and `EntityResolve` rides the federation surface; each needs the same
one-sentence verdict.
