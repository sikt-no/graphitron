---
id: R525
title: "Fix probabilistic false-failure in the tenant-fan-out redaction test predicate"
status: Backlog
bucket: testing
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Fix probabilistic false-failure in the tenant-fan-out redaction test predicate

`TenantFanOutExecutionTest.claimedButUnmappedTenant_failsTheRequestBeforeAnySql` fails intermittently with no product defect. Its redaction assertion proves the unhosted tenant key `99` never reaches the wire by checking `!e.getMessage().contains("99")`, but the redacted message embeds a random correlation UUID ("An error occurred. Reference: <uuid>."), and a 32-hex-digit UUID contains the substring `99` in roughly one draw out of ten. When it does, the guard trips and the test fails; it then passes on rerun with a luckier UUID, which is why it has read as environment flakiness. Fix by making the predicate ignore the reference token: strip the "Reference: <uuid>" tail before the contains check, or assert the full redacted-message shape with a regex and check for `99` only outside the UUID group. Worth a glance at the sibling tenant tests for the same pattern while there.
