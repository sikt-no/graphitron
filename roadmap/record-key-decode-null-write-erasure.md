---
id: R534
title: "Record key decodes erase explicit-null column writes via jOOQ from() flag reset"
status: Backlog
bucket: architecture
priority: 5
theme: codegen-correctness
depends-on: []
created: 2026-07-25
last-updated: 2026-07-25
---

# Record key decodes erase explicit-null column writes via jOOQ from() flag reset

In a generated record-instantiation helper (`JooqRecordInstantiationEmitter`), key decodes load via `Record.fromArray`, whose `from()` null-skip semantics reset the touched flag of every null-valued column record-wide. Column bindings are emitted before key decodes, so a present identity field silently erases any explicit-null column write made earlier in the same helper: `customerUpsert(in: {identity: {...}, details: {firstName: null}})` leaves `first_name` untouched, while the same input without the identity group writes `NULL`. Whether a column write survives thus depends on an unrelated sibling field's presence, which no author would predict. Both halves are execution-pinned (`GraphQLQueryTest#customerUpsert_explicitNullNestedLeaf_collapsesToOmitted` / `#customerUpsert_explicitNullNestedLeaf_noIdentityDecode_writesNull`) and the mechanism is documented on `JooqRecordInstantiationEmitter#emitKeyDecode`, discovered by R527's truth-probe (the prior prose blamed graphql-java coercion, falsely — coercion retains nested explicit-nulls at every depth). Candidate fixes: emit key decodes before column bindings, or load decoded keys via per-column `set()` instead of `fromArray`. Either is a behavior change to pinned execution contracts (the collapse test and the service javadoc treat the erasure as the current contract), so it needs its own design pass, not a drive-by.
