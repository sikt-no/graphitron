---
id: R526
title: "Correct stale generator and model comment claims routed out of the javadoc sweep"
status: Backlog
bucket: cleanup
priority: 4
theme: model-cleanup
depends-on: []
created: 2026-07-24
last-updated: 2026-07-24
---

# Correct stale generator and model comment claims routed out of the javadoc sweep

The comment-trimming sweep (`roadmap/javadoc-verbosity-alignment-sweep.md`) found comment claims that are factually wrong or reference dead code, and routed them here instead of rewriting them, because each needs a code-level decision (fix the claim, fix the code, or delete the dead API) that a comment-only sweep must not make. The worklist, with locations verified at routing time:

- `GraphitronType.ResultType#fqClassName` javadoc carries a may-be-null caveat, but every construction path supplies a non-null value; either enforce non-null or correct the caveat.
- `GraphitronType`'s InputType javadoc has a garbled "(or `@table`)" parenthetical that parses to nothing; reconstruct the intended sentence.
- `TypeFetcherGenerator` around L2085 has a comment contradicting the `DSL.noCondition()` emission directly below it.
- `TypeFetcherGenerator` claims "Mapped is not produced yet" in two places whose adjacent throw-message string literals repeat the stale claim; the `ErrorChannel.Mapped` arm is live.
- `RecordBindingResolver#fromAnyProducer` has zero callers; delete it or document why it must stay.
- `MultiTablePolymorphicEmitter` (two comments) and `RowsMethodCall` cite the removed `buildSplitQueryDataFetcher`/`buildRecordBasedDataFetcher` methods; repoint to the live dispatch path.
