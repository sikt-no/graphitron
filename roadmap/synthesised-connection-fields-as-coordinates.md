---
id: R562
title: "Classify a synthesised connection type's totalCount/facets fields as coordinates"
status: Backlog
bucket: architecture
priority: 5
theme: classification-model
depends-on: []
created: 2026-07-31
last-updated: 2026-07-31
---

# Classify a synthesised connection type's totalCount/facets fields as coordinates

A synthesised connection type's `totalCount` and `facets` fields exist only as emit: the
classifier mints no coordinate for them, so nothing in the fact base says a covered connection
carries a count or a facet aggregate. The observable gap is the `Operation.Count` and
`Operation.Facet` arms of the `OPERATION_ARMS` obligation (`ExemptionRegistry.OPERATION_KNOWN_GAPS`),
which no corpus fixture can reach because no classified coordinate ever carries either arm. The
exemption reasons still cite the connection launcher's `ConnectionResult` carrier fork, but that
blocker is discharged (`ResultShape.Connection` carries the helper, carrier and facet plan today);
the live blocker is this model question, and the corpus-command item (R543, whose in-scope
correction re-anchors both exemption reasons) names this item as its owner.

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
