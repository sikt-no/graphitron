---
id: R591
title: "Member payload storage home: the three leaf-homed axes"
status: Backlog
bucket: architecture
theme: classification-model
depends-on: []
created: 2026-08-04
last-updated: 2026-08-04
---

# Member payload storage home: the three leaf-homed axes

After the operation-relation programme, `SqlGeneratingField` is still the storage home for the condition, orderBy and paginate payloads (`filters()`, `orderBy()`, `pagination()` on the seal): on those three axes the `OperationMember` rows are a view over the leaf rather than the reverse, while the write, pivot and lookup axes carry their payloads member-first. The payloads are shared by reference, so the "one payload, one member row" property holds in the object-identity sense, and the leaves dissolved regardless; the residual is directional consistency, not a correctness gap. Decide whether the three leaf-homed axes should re-home onto their member rows (making the member relation the storage home on every axis) or whether the leaf-homed shape is the right end state for payloads that are total on the `TableTargetField` seal, and record the decision where the next dissolution programme will find it. The independent gate review of the programme recorded this residual as Backlog-worthy rather than programme-reopening.
