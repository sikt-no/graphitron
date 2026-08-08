---
id: R611
title: "Nudge commercial-edition jOOQ consumers, whose coordinate the currency check never sees"
status: Backlog
bucket: feature
priority: 5
theme: diagnostics
depends-on: []
created: 2026-08-08
last-updated: 2026-08-08
---

# Nudge commercial-edition jOOQ consumers, whose coordinate the currency check never sees

The dependency-currency nudge watches exactly one jOOQ coordinate, `org.jooq:jooq`, which is the
open-source edition. jOOQ's commercial distributions ship under different group ids
(`org.jooq.pro`, `org.jooq.pro-java-<n>`, `org.jooq.trial`, `org.jooq.trial-java-<n>`), so a
commercial-edition consumer resolves no watched coordinate at all and falls into the
absent-coordinate silence case: never nudged, at any distance behind, with nothing in the build
saying why. That is not a rare shape here. `docs/dependencies.adoc` states that we ourselves use
the commercial licence, and the Oracle dialect the `<devDatabase>` block accepts is commercial-only,
so the population the nudge silently skips plausibly includes most Sikt subgraphs. The same page's
new "Staying current" section promises a consumer that "the build says so" when they lag, which for
those consumers is not true.

Nothing about this is a defect in the currency check as specified: the coordinate set was ruled
deliberately, and an absent coordinate being silent is the correct behaviour for a coordinate the
generated sources genuinely are not built against. What is missing is that the commercial editions
are the *same library* under a different group id, not a different dependency.

Two decisions the Spec owes. First, which group ids to watch, and whether the list is enumerated or
matched by prefix: `pro-java-<n>` and `trial-java-<n>` are open-ended, so an enumeration goes stale
at each new baseline Java release while a prefix match risks admitting something unintended. Second,
what the reference side compares against, since graphitron itself builds against `org.jooq:jooq` and
the plugin realm therefore carries no commercial artifact: comparing a commercial observed version
against the open-source reference is right on the version numbers (the editions share a release
line) but means one `WatchedDependency` no longer maps one-to-one onto a single coordinate, which is
the assumption `WatchedDependency.of(groupId, artifactId)` and the `EnumMap` keying currently rest
on. A consumer carrying both editions at once also needs a stated answer.

The suppression id should stay `jooq-version-lag` either way, so a consumer who silences the nudge
keeps it silenced across an edition switch. `docs/dependencies.adoc` gains a sentence when this
lands, since it currently describes coverage the check does not have.

