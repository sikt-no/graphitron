---
id: R1031
title: "A field rejected at classification voids its subtree tenant context"
status: Backlog
bucket: bug
priority: 4
theme: diagnostics
depends-on: []
created: 2026-10-14
last-updated: 2026-10-14
---

# A field rejected at classification voids its subtree tenant context

## Goal

In a database-per-tenant build, a field that classification rejects outright (its coordinate becomes a `GraphitronField.UnclassifiedField`, as `@notGenerated` does) reports its own error and does not also void the tenant context of every type below it. Today the tenant fold (`TenantBindingIndex.Fold`) records no site for such a field, since `UnclassifiedField` is not an `OutputField`, while `buildEdges` still records its edge from the SDL; `edgeEstablishesContext` answers false for an edge with no sites, so every tenant-scoped field below it rejects with "no ancestor established a tenant context". sis hit this on 2026-10-14 with `Query.sisNode(id: ID!): Node @notGenerated @inaccessible`: one rejected root returning `Node` voided the context of every node type, about 680 follow-on errors. Removing the directive cleared it.

R1027 makes tenant rejections (`Reading.Declined`, `Reading.Unbound` and the dispatch entries) judge their subtree as every offered fix leaves it. That argument rests on the rejected site's model: its reach, its reading, and the fixes its rejection text offers. A classification-rejected field has none of those, so the same posture needs its own argument, which is why the case left R1027. The open question for the Spec is what such an edge hands down: nothing (today), a context (the `Node` case, where the fixed field is a node lookup that routes), or a judgment from the SDL return type alone (does its closure hold a tenant-scoped `@table` type). Whatever the answer, a passing build has no `UnclassifiedField`, so it cannot change; the risk is only masking a child's real error until the next build, which R1027 refused for `@globalData`.

The missing rejection message for the sis field is a separate defect, R1030.
