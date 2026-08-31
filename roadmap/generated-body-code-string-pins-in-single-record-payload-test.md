---
id: R892
title: "A pipeline test pins a generated fetcher body with code strings"
status: Backlog
bucket: hygiene
priority: 3
theme: testing
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# A pipeline test pins a generated fetcher body with code strings

`SingleRecordPayloadPipelineTest`'s direct-`@table` two-step case asserts on the *text* of a
generated fetcher body: it counts `transactionResult(` occurrences, does `body.indexOf(rowsName +
"(keys, env)")` arithmetic against the transaction call site, and pins `.doesNotContain(".select(")`
on the fetcher beside `.contains(".select(")` on the reentry companion.
`docs/architecture/principles/development-principles.adoc` bans code-string assertions on generated
method bodies at every tier ("they test implementation, not behaviour, and break on every
refactor; the compile and execution tiers replace them"), and notes the ban is review-enforced
rather than build-enforced, which is how these survived. They predate the ban's current phrasing:
`git blame` puts them on R75 (the direct-`@table` two-step emit and its durability pins) and
R314 slice 4 (the named DML reentry unit), with R482 passing over them for a different reason.

What they are reaching for is a real invariant and worth keeping: the write commits before any
follow-up read can fail, which currently survives as *call ordering* inside one emitted body — the
`rows<Name>(keys, env)` call sits after the `transactionResult(...)` call site, and the companion,
not the fetcher, owns the `.select(...)`. The task is to restate that at a tier that can see it
rather than to delete the coverage: the durability claim is an execution-tier fact (a committed
write survives a failing follow-up read), and "which method owns the SELECT" is a structural fact
about the emitted `TypeSpec` that can be asserted over `MethodSpec` identity and call-graph shape
instead of over rendered source text. R885's Done gate set the precedent for the second half,
narrowing two such pins in `TypeFetcherGeneratorTest` into one explaining helper rather than
multiplying them; R880's set the precedent for the first, deleting a `FetcherPipelineTest`
code-string pin and restating its claim at the execution tier.

Found during R687's In Review -> Done gate, in the same test class R687 extended. Not R687's to
fix: that item added no code-string assertions (its own pins are classification verdicts, rejection
messages and a `TableRef` equality), and the delivered change does not touch these lines.
