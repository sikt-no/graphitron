---
id: R56
title: "Extract `ConnectionPromoter` from `GraphitronSchemaBuilder`"
status: Backlog
bucket: architecture
priority: 3
theme: structural-refactor
depends-on: []
---

# Extract `ConnectionPromoter` from `GraphitronSchemaBuilder`

`GraphitronSchemaBuilder.java` (622 lines) is mostly orchestration glue, but it bundles ~150 lines of one cohesive sub-concern: turning `@asConnection` carrier fields into proper Connection-typed fields and synthesising the supporting Connection / Edge / PageInfo types. The pieces are spread across `promoteConnectionTypes`, `rewriteCarrierField`, `buildSynthesisedPageInfo`, `resolveDefaultFirstValue`, `resolveConnectionName`, plus the small `baseTypeName` / `capitalize` helpers. Lifting this into a `ConnectionPromoter` class gives the concern its own file and its own focused test surface (today's coverage is shaped around the schema-build pipeline, not the promotion logic). This is a smaller-scale instance of R6's "state 2" pattern (a cohesive concern factored as private methods rather than its own type), filed separately because the motivation here is testability and scope clarity, not the cross-arm duplication that drives R6. Not blocking anything; pick up if and when someone is in `GraphitronSchemaBuilder` for an unrelated reason.
