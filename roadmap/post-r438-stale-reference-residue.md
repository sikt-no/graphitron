---
id: R443
title: "Post-R438 stale-reference residue: ConditionResolution javadoc + fkjoin-alias-dead-storage item"
status: Backlog
bucket: structural
priority: 5
theme: structural-refactor
depends-on: []
created: 2026-07-08
last-updated: 2026-07-08
---

# Post-R438 stale-reference residue: ConditionResolution javadoc + fkjoin-alias-dead-storage item

R438's In Review to Done review surfaced two staleness residues its own stale-reference sweep did not cover; captured here as a small cleanup since R438 is closed.

1. **`BuildContext.ConditionResolution` javadoc drift.** The record's javadoc still asserts "exactly one of `ref` and `error` is non-null," but `resolveConditionRef` returns `(null, null)` in two cases: when `className` / `methodName` / `svc` is absent, and when `reflectTableMethod` fails. That both-null case is exactly what R438's review-fix (`b0ab513`) added caller guards for (`res.ref() == null` checks in the three `parsePathElement` branches). Correct the javadoc to state that a `(null, null)` result means "unresolved" and that callers must guard `res.ref() == null` in addition to `res.error() != null`.

2. **`fkjoin-alias-dead-storage` Backlog item is stale.** `roadmap/fkjoin-alias-dead-storage.md` references the deleted types `FkJoin.alias` / `ConditionJoin.alias` and a stale `BuildContext.java:694` line number; R438's reshape moved that concern onto `Hop.alias`. Refresh the item's prose against `Hop.alias` (the dead-storage question survives the reshape, just under a new component) or retire it if the alias is now consumed.

Both are doc/comment-only; neither changes generated output or behavior.
