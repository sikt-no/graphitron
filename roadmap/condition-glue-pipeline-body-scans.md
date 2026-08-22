---
id: R561
title: "ConditionGluePipelineTest still scans glue bodies for the shared decode-helper call"
status: Backlog
bucket: test-quality
priority: 3
theme: testing
depends-on: []
created: 2026-07-30
last-updated: 2026-07-30
---

# ConditionGluePipelineTest still scans glue bodies for the shared decode-helper call

`ConditionGluePipelineTest.twoQueryFields_sharingNodeIdType_emitOneSharedHelper` asserts
`assertThat(body).contains("decodeBarRowsOrThrow(")` against `method.code().toString()` for both
condition methods, the code-string-on-generated-body pattern
`docs/architecture/principles/development-principles.adoc` bans at every tier. The lines predate
the condition-command reshape (they rode the file's rename from `QueryConditionsPipelineTest`) and
carry unique signal the structural assertions beside them do not: `hasSize(1)` on the helper set
proves the registry deduplicated, but only the body scan proves *both* methods reference the shared
helper rather than one of them inlining its own decode. The same file's
`multiHopIdentityCarryingLift_emitsHelperOnLiftedTuple` explicitly declines the scan citing the
ban, so the file is internally inconsistent about the rule. Resolve it structurally: either ask
the cross-reference question against the `CodeBlock` tree (the direction R554 sets for the
`TypeSpecAssertions` string-scan helpers, which this case should follow or fold into), or decide
the dedup fact is fully pinned by helper-set cardinality plus the compilation tier and delete the
two scans. Raised by the independent Done-gate reviewer on the condition-command item, which was
explicitly not asked to fix pre-existing debt.
