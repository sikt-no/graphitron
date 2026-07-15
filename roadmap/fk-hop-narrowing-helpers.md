---
id: R485
title: "Model-level isFkHop/pairsOf helpers for JoinStep narrowings"
status: Backlog
bucket: structural
priority: 6
theme: classification-model
depends-on: []
created: 2026-07-15
last-updated: 2026-07-15
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
