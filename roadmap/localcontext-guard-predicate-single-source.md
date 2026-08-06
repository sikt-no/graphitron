---
id: R533
title: "Single-source the null-source-guarded data-channel predicate as a classifier-assigned fact"
status: Backlog
bucket: architecture
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-07-25
last-updated: 2026-08-06
---

# Single-source the null-source-guarded data-channel predicate as a classifier-assigned fact

"This fetcher short-circuits on a null source" is a fact asserted twice with nothing binding the two sites: the emitter decides it structurally (the source-shape fork in `TypeFetcherGenerator.buildBatchedDataFetcher` emits the `env.getSource() == null` prelude on the Record arm and the `instanceof Success` narrowing on the Outcome arm, plus `FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue`'s guard), while `GraphitronSchemaValidator.isLocalContextGuardedDataChannel` re-enumerates the safe variants as a hand-maintained allow-list whose javadoc pleads "removing the guard from an existing emitter arm must remove the variant here". Per "Decide once, at the parse boundary" (two consumers evaluating the same predicate over model facts means the branch belongs in the model), lift the guarded-data-channel verdict into a classifier-assigned fact (an accessor on the field variant or a capability), have both the emitter prelude fork and the validator read it, and delete the allow-list switch. Also decide the currently-open edge the hand-list leaves ambiguous: `BatchedLookupTableField` routes through the same guarded builder arms but is not admitted by the validator; single-sourcing forces that verdict to be stated once, with validation coverage. Routed from the stale-comment corrections item that truth-verified the allow-list's claims against the live emitter arms.

## Fact-base note (2026-08-06)

The single source this item asks for is a derivation view both consumers read once classification migrates: the guarded-data-channel verdict becomes a claim/slot fact, and `isLocalContextGuardedDataChannel`'s hand-maintained allow-list dissolves rather than moves. The open lookup-shaped edge (now `BatchedTableField` plus a lookup member, the named leaf having merged in R563 slice 6a) is then a row that exists or does not.
Context and the whole-board picture: `roadmap/audits/2026-08-06-fact-base-impact-sweep.md`.
