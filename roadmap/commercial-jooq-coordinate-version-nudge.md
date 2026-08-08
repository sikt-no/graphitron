---
id: R611
title: "Nudge commercial-edition jOOQ consumers, whose coordinate the currency check never sees"
status: In Progress
bucket: feature
priority: 3
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

Nothing about this is a defect in the currency check as it was specified: the coordinate set was
ruled deliberately, and an absent coordinate being silent is correct for a coordinate the generated
sources genuinely are not built against. What that ruling missed is that the commercial editions are
the *same library* under a different group id, not a different dependency. Under the nudge's own
framing the consequence is not a gap at the margin: an advisory that is inert for most of the people
it was built for is not doing the job the item claimed, and the docs page shipped a promise the
build does not keep for them.

## Settled by the requester

**The editions are version-synchronised.** Commercial and open-source jOOQ ship the same release
line with the same numbers, so an observed commercial version compares directly against the
open-source reference with no mapping, no per-edition reference, and no second number to maintain.
This is the fact the whole item rests on, and it is why the change is small. Record it as an
assumption rather than an invariant: nothing in graphitron enforces it, and if the editions ever
diverge in numbering the comparison goes quietly wrong rather than loudly. Nothing in the plan below
should acquire machinery to defend against that.

## Scope

jOOQ only. graphql-java has no edition split, so `WatchedDependency.GRAPHQL_JAVA` and its rule are
untouched. No new `LintRule`: the suppression id stays `jooq-version-lag`, so a consumer who has
already silenced the nudge keeps it silenced across an edition switch, and
`LintRuleRegistryCoverageTest`'s hard-listed `Source.CODEGEN` set does not move.

## The coordinates

Open source is `org.jooq:jooq`. The commercial and trial distributions keep the artifact id `jooq`
and vary the group id: `org.jooq.pro` for the current baseline JDK, `org.jooq.pro-java-<n>` for the
older baselines still supported, and `org.jooq.trial` / `org.jooq.trial-java-<n>` for the trial
distribution.

**Match by group-id prefix, not by enumeration.** The suffix set is not fixed: `org.jooq.pro` tracks
whatever the current baseline JDK is, and each jOOQ baseline bump rotates a new `-java-<n>` into the
supported set and eventually drops an old one. An enumerated list therefore goes stale at exactly
the moment a consumer upgrades their JDK baseline, and its failure mode is silence, which is the
same failure this item exists to fix and the same one that took a whole release to notice. Proposed
predicate: artifact id equal to `jooq`, and group id equal to `org.jooq` or beginning with
`org.jooq.pro` or `org.jooq.trial`.

Pinning the artifact id to `jooq` exactly is what keeps the prefix safe: `org.jooq:jooq-codegen` and
its commercial twins are not the runtime library and must stay unmatched. `DependencyVersionDecodeTest`
already carries a row asserting `jooq-codegen` is not `jooq`, so that guard exists and needs
extending rather than inventing.

## The structural change: an observed coordinate is no longer the watched one

`WatchedDependency` currently is a coordinate: `of(groupId, artifactId)` maps one pair to one
constant, and the advisory renders `dep.coordinate()` as the thing to bump. That breaks here in a
way worse than silence. A Pro consumer told to bump `org.jooq:jooq` is being told to switch to the
open-source edition, which for an Oracle or SQL Server subgraph does not work at all. The message
must name the coordinate the consumer actually resolved.

So the observed coordinate has to travel with the observed version. Two shapes:

1. `DependencyVersions.observed()` carries `(coordinate, version)` per watched dependency instead of
   a bare version string, and the message renders the observed coordinate.
2. `WatchedDependency` grows a matcher plus a separate canonical display coordinate.

**Recommend (1).** It is the smaller change, it keeps `WatchedDependency` as the identity of a
*library* rather than of an artifact (which is what the coordinate split has just shown it needs to
be), and the coordinate is data the boundary already has in hand at decode time. The reference side
needs no coordinate at all: the message reports the reference version number and nothing else about
where it came from.

### Where the observation is selected

Prefix matching makes a watched *library* multi-valued on the observed side: two artifacts can now
map to `JOOQ`. `AbstractRewriteMojo.versionsOf` currently collapses that with `putIfAbsent`, under a
javadoc reading "First occurrence wins; Maven has already mediated, so a coordinate appears once".
Maven mediates per coordinate, not per library, so that sentence stops being true the moment the
predicate widens, and which observation survives becomes a function of artifact-set iteration order.
Left alone it is a silent order-dependence, so the plan settles it rather than leaving an implementer
to keep the existing line and not notice.

Selecting the surviving observation at the boundary is the wrong repair. Comparing release lines *is*
the decision, `DependencyVersionWarnings.minorLine` is where it lives, and the boundary's stated job
is turning artifacts into pairs and nothing else (see "Decide once, at the parse boundary" in
`docs/architecture/explanation/development-principles.adoc`). Doing it there would push version
ordering across the Maven boundary for the benefit of one edge case.

So shape (1) carries *every* match: `observed()` becomes a per-dependency collection of
`(coordinate, version)`, the boundary appends rather than de-duplicates, and
`DependencyVersionWarnings` picks the one it advises on. The reference side keeps its bare
`Map<WatchedDependency, String>`; the plugin realm carries one jOOQ and no coordinate is reported
from it. `versionsOf`'s javadoc is rewritten in the same pass rather than left describing the old
contract.

## Both editions at once: settled

**A consumer carrying both editions at once** is reachable in practice, a transitive open-source jOOQ
alongside a direct Pro one, and the plan should not leave it to be discovered. Ruling: one advisory
per watched library, computed on the *lowest* observed line and naming that line's own coordinate,
because the lowest line is the one actually holding the consumer back. That preserves the invariant
the structural change exists for, since the coordinate in the message is always one the consumer's
build actually resolved, never one graphitron guessed. A mixed-edition classpath is its own problem
and not this advisory's to report; the nudge should not grow a second thing to say.

Two editions on the same line is a tie, and a tie needs a defined answer or the message text moves
between runs on the same project. Lowest line first, then lowest coordinate string.

## Testing

The matching half lands in `DependencyVersionDecodeTest`, which gains rows for `org.jooq.pro`,
`org.jooq.pro-java-<n>` and `org.jooq.trial-java-<n>` observed, rows holding `jooq-codegen` unmatched
under a commercial group id as well as the open-source one, and a row where both editions resolve,
asserting the decode carries both rather than dropping one.

The deciding half lands in the unit tier, in `DependencyVersionWarningsTest`: that the message names
the *observed* coordinate rather than the canonical one, which is the case that would catch a Pro
consumer being told to bump `org.jooq:jooq`; that the lowest line wins when two editions are
observed, asserted under both input orders so the order-independence is pinned rather than assumed;
and that the tie-break is the coordinate string.

**The invoker tier cannot cover this, and the plan should say so rather than leave an implementer
trying.** Only the open-source edition is on Maven Central; commercial and trial artifacts are
served from jOOQ's own licensed repository. Every candidate group id was probed against Central and
all return 404, so no IT can resolve a real commercial artifact, and a hand-installed stub would pin
nothing the decode tier does not already pin. This is a genuine asymmetry with the open-source path,
whose end-to-end route the `dependency-version-lag` IT does exercise, and it is worth naming as a
known limit of the coverage rather than papering over.

## Documentation

`docs/dependencies.adoc`'s "Staying current" section describes coverage the build does not currently
have. It gains a sentence when this lands. Settled at the review: do not correct the page ahead of
the fix, since a doc that describes a gap and then describes the gap being closed two commits later
is more churn than the exposure warrants. If the item stalls, correcting it becomes the right call.

