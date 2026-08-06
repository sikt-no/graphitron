---
id: R562
title: "Classify a synthesised connection type's totalCount/facets fields as coordinates"
status: Backlog
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-07-31
last-updated: 2026-08-06
---

# Classify a synthesised connection type's totalCount/facets fields as coordinates

A synthesised connection type's `totalCount` and `facets` fields exist only as emit: the
classifier mints no coordinate for them, so nothing in the fact base says a covered connection
carries a count or a facet aggregate. The observable gap is the `Operation.Count` and
`Operation.Facet` arms of the `OPERATION_ARMS` obligation (`ExemptionRegistry.OPERATION_KNOWN_GAPS`),
which no corpus fixture can reach because no classified coordinate ever carries either arm. The
exemption reasons used to cite the connection launcher's `ConnectionResult` carrier fork; the
corpus-command item (R543) re-anchored them on the live ground, since that fork is discharged
(`ResultShape.Connection` carries the helper, carrier and facet plan today). The blocker is this
model question, and both reason strings name this item as its owner.

The question to answer at Spec: should the synthesis step register the minted connection type's
`totalCount`/`facets` fields as classified coordinates in the fact base, each carrying its
operation arm, the way authored fields are classified? Deciding yes gives the arms reachable
corpus fixtures (retiring both exemption rows via the covered-entry ratchet), gives the fields a
home in coordinate-keyed relations, and aligns the synthesised surface with the
declared-equals-produced treatment `@synthesises` already gives the minted types themselves.
Deciding no needs a stated ground for why these two fields stay emit-only while their containing
types are model facts. Either answer should say where the `(table, condition)` binding for
`totalCount` and the facet fragment refs live if the fields do become coordinates (today they
ride the launcher row's result shape).

## Fact-base note (2026-08-06)

Half decided by R595: macro expansion runs inside capture with synthesis-provenance rows, so synthesized coordinates are existence facts by construction. The question narrows to whether the minted connection's `totalCount` / `facets` field coordinates get demand rows and where their slot facts live; re-pose it against the capture walk rather than "should the synthesis step register coordinates". (The exemption arms this item cites are `OperationMember.Count` / `Facet` under `MEMBER_KNOWN_GAPS` since R563.)
Context and the whole-board picture: `roadmap/audits/2026-08-06-fact-base-impact-sweep.md`.
