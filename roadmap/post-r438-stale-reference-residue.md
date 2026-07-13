---
id: R443
title: "Post-R438 stale-reference residue: ConditionResolution javadoc"
status: Backlog
bucket: structural
priority: 5
theme: docs
depends-on: []
created: 2026-07-08
last-updated: 2026-07-13
---

# Post-R438 stale-reference residue: ConditionResolution javadoc

R438's In Review to Done review surfaced two staleness residues its own stale-reference sweep did not cover; captured here as a small cleanup since R438 is closed. The second residue (the stale `fkjoin-alias-dead-storage` Backlog item, R120) was resolved by discarding that item on 2026-07-13: R438's reshape deleted the types it named, and `Hop.alias()` is now consumed by the routine/chain join emitter, so its "retire it if the alias is now consumed" branch applied (see the changelog entry). One residue remains:

**`BuildContext.ConditionResolution` javadoc drift.** The record's javadoc still asserts "exactly one of `ref` and `error` is non-null," but `resolveConditionRef` returns `(null, null)` in two cases: when `className` / `methodName` / `svc` is absent, and when `reflectTableMethod` fails. That both-null case is exactly what R438's review-fix (`b0ab513`) added caller guards for (`res.ref() == null` checks in the three `parsePathElement` branches). Correct the javadoc to state that a `(null, null)` result means "unresolved" and that callers must guard `res.ref() == null` in addition to `res.error() != null`.

Doc/comment-only; changes no generated output or behavior.
