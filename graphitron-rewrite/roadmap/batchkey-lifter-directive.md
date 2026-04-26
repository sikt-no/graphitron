---
title: "`BatchKey` lifter directive"
status: Backlog
bucket: architecture
priority: 1
---

# `BatchKey` lifter directive

Mechanism for schema authors to supply a DTO-to-key conversion, enabling DataLoader batching on DTO parents; feeds the existing column-keyed path once `BatchKey.ObjectBased` removal lands. Co-closes the `RecordTableField` / `RecordLookupTableField` missing-FK-path rejection for DTO parents.
