---
id: R485
title: "Model-level isFkHop/pairsOf helpers for JoinStep narrowings"
status: Backlog
bucket: structural
priority: 6
theme: classification-model
depends-on: []
created: 2026-07-15
last-updated: 2026-08-21
---

# Model-level isFkHop/pairsOf helpers for JoinStep narrowings

R438's self-review cleanup (1), spun out of R431 per its spec's explicit option ("splitting it to
a follow-on item is an acceptable outcome if this item runs long"; R431 slice 4 landed the coupled
cleanup (2), the bridging-join consolidation into `JoinPathEmitter`). The FK-hop narrowing idiom,
`instanceof JoinStep.Hop h && h.on() instanceof On.ColumnPairs`, plus blind
`(On.ColumnPairs) hop.on()` casts, appears inline roughly forty times across ~16 main-source files
(re-count at pickup: `grep -rn "instanceof JoinStep.Hop" graphitron/src/main` and
`"instanceof On.ColumnPairs"`), each a hand-rolled repetition of one model question ("is this hop
FK-derived, and what are its column pairs"). A model-level `isFkHop(JoinStep)` / `pairsOf(JoinStep)`
helper pair (natural home: `JoinStep` or a static on `On`) replaces the inline narrowings; the
exhaustive sealed-switch sites are proper dispatch and stay. `TestFixtures.isFkHop` already exists
as a test-side copy of the predicate and should fold onto the model helper. Acceptance mirrors
R431's: generated output byte-identical, the diff audited in isolation; full reactor green.

Scope note (2026-08-19, revised 2026-08-21): R705 changes this item's census in one way, not the
two an earlier version of this note claimed. It retires `FkHop` outright, whose `narrow` was one of
the census's members, and introduces one new hop-typed carrier of its own (`ReachPath`, whose
compact constructor is the single narrowing site for the condition filter rail).

It does *not* lift the declared type of the path-carrying components. An earlier R705 draft lifted
four of them (`ParsedPath.elements`, `BodyParam.RemoteColumnPredicate.joinPath`, and the `joinPath`
components of `ArgumentRef.ScalarArg.ColumnBackedArg` and `InputField.ColumnBackedReferenceField`)
from `List<JoinStep>` to `List<JoinStep.Hop>`; that was dropped at R705's third Spec review, because
Java generics are invariant and `ParsedPath.elements` alone feeds 47 read sites, so the lift is a
compile error at every consumer that has not lifted with it. The reasoning is in R705's Review
resolutions section.

So the `instanceof JoinStep.Hop` narrowing sites on those paths survive R705 and remain this item's
census. Whether the right consolidation is the helper pair or the type lift is still this item's
call, and the lift's real cost is now measured: see R705 for the site counts. Re-count at pickup as
already instructed above, after R705 lands if it is in flight.
