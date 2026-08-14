---
id: R665
title: "Residue pins from the R648 gate: assert the service-leaf key pins fire, and name the wrong-table typed-record parent in the no-producer diagnostic"
status: Backlog
bucket: testing
priority: 8
theme: service
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# Residue pins from the R648 gate: assert the service-leaf key pins fire, and name the wrong-table typed-record parent in the no-producer diagnostic

Two small residues from the R648 In Review -> Done gate, filed rather than held against the approval. First, the R648 spec's unit-test bullet asked that one case assert the non-null pins on `ChildField.ServiceTableField` / `ChildField.ServiceRecordField` (the `sourceKey` and `keySource` compact-constructor `requireNonNull`s) actually fire, not merely that construction sites satisfy them; the delivery updated every direct-construction site but shipped no fires-assertion, so removing a `requireNonNull` would today surface only as a downstream NPE in `ChildField.sourceShape()`. Second, a diagnostic wording nit: in `FieldBuilder.resolveServiceKeySource`, a `JooqTableRecordType` parent holding a record of a *different* table than the `Sources` element type names falls to the `JooqRecordCarrier` switch arm and is rejected with "carries no backing class that can produce one", which is untrue for a typed record parent (it carries a backing class; it is a record of the wrong table). The advice half of the message is still correct; the description half should name the actual shape.
