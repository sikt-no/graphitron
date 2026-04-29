---
id: R1
title: "`BatchKey` lifter directive"
status: Backlog
bucket: architecture
priority: 1
theme: service
depends-on: []
---

# `BatchKey` lifter directive

Mechanism for schema authors to supply a DTO-to-key conversion, enabling DataLoader batching on DTO parents. `BatchKey.ObjectBased` has been removed; free-form DTO sources are now rejected at classification time with a build error pointing here. This feature re-enables DTO-parent DataLoader batching by feeding the existing column-keyed path via a developer-supplied lifting function. Co-closes the `RecordTableField` / `RecordLookupTableField` missing-FK-path rejection for DTO parents.
