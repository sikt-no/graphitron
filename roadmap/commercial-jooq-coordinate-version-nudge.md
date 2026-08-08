---
id: R611
title: "Nudge commercial-edition jOOQ consumers, whose coordinate the currency check never sees"
status: In Review
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

## What shipped

The matching, the carrier and the selection landed together; widening the predicate is what makes
the observed side multi-valued, so the compiler admitted no smaller step.

**A watched dependency is a library, not a coordinate.** Each `WatchedDependency` constant carries
its canonical group id plus the group-id prefixes its other editions ship under (`org.jooq.pro`,
`org.jooq.trial`), and matching pins the artifact id exactly. Prefix rather than enumeration, so a
consumer does not drop out of the advisory's sight the moment they move their JDK baseline; artifact
id exact, because that is what keeps the prefix from swallowing `jooq-codegen` and its commercial
twins. The prefixes are deliberately narrower than a bare `org.jooq`, which would also match any
future unrelated coordinate in that namespace.

**The observed side is multi-valued and carries coordinates.** `DependencyVersions.observed()` is a
`Map<WatchedDependency, List<ObservedVersion>>`, and the new `ObservedVersion` pairs the resolved
coordinate with the resolved version. `AbstractRewriteMojo.observedVersionsOf` appends every match
rather than collapsing on first occurrence: Maven mediates per coordinate and not per library, so
first-occurrence-wins would have made the surviving observation a function of artifact-set iteration
order. The reference side kept its bare version per dependency and moved into its own
`referenceVersionsOf`, since only the consumer side is scoped and only the consumer side can put one
library at several coordinates; the two stopped sharing one scope-parameterised method.

**The selection lives in the interior.** `DependencyVersionWarnings.lowestLine` picks the observation
to advise on: lowest release line, ties broken on the coordinate string so the message text does not
move between runs on an unchanged project. One advisory per library however many editions lag, and
`MinorLine` became `Comparable` so the ordering is stated once. Comparing release lines is the
decision the advisory exists to make, and keeping it here rather than at the boundary is "Decide
once, at the parse boundary" in `docs/architecture/explanation/development-principles.adoc`.

**Decided during implementation, not settled in the plan.** An observation whose version does not
decompose into a line is passed over rather than allowed to speak for the others: it cannot be
ordered, and the readable observations are still true. Silence remains the answer when nothing
readable is left, so the existing silence case is preserved per observation rather than per library.

## Testing

The matching half is in `DependencyVersionDecodeTest`: every commercial and trial group id observed as
the same watched library and carrying its own coordinate, `jooq-codegen` held unmatched under all four
group-id shapes, a group id merely sharing the `org.jooq` namespace held unmatched, and both editions
at once carried rather than one dropped.

The deciding half is in the unit tier, in `DependencyVersionWarningsTest`: the message names the
resolved commercial coordinate and provably does not name `org.jooq:jooq`, which is the assertion that
catches a Pro consumer being told to switch editions; the lowest line wins under both input orders, so
the order-independence is pinned rather than assumed; the lowest line wins *when it is the commercial
one*, which is what stops the selection collapsing into "prefer open source"; the tie-break is the
coordinate string, again under both orders; one advisory however many editions lag; and an unreadable
observation neither speaks nor silences the readable ones.

**The invoker tier cannot cover this**, so no implementer should spend the attempt. Only the
open-source edition is on Maven Central; commercial and trial artifacts are served from jOOQ's own
licensed repository. Every candidate group id was probed against Central and all return 404, so no IT
can resolve a real commercial artifact, and a hand-installed stub would pin nothing the decode tier
does not already pin. This is a genuine asymmetry with the open-source path, whose end-to-end route
the `dependency-version-lag` IT does exercise, and it is worth naming rather than papering over.

## Documentation

`docs/dependencies.adoc`'s "Staying current" section gained a paragraph: every jOOQ edition counts, the
warning names the coordinate the build actually resolved so a commercial subgraph is never told to bump
its way onto the open-source edition, and two editions at once are spoken about on the lower line.

## Known limit worth a reviewer's eye

Once jOOQ drops an older baseline distribution, a consumer on `org.jooq.pro-java-<n>` can be told to
bump a coordinate that has no such version, because the fix for them is a JDK-baseline move rather
than a version bump. Nothing was added to defend against it: the advisory already says nothing is
broken and offers the rule id to silence, and the alternative is per-distribution knowledge of jOOQ's
support window, which is exactly the machinery the version-synchronisation assumption above forbids.
Named here so the Done gate sees it was considered rather than missed.

## Retired vocabulary

- `WatchedDependency.coordinate()`, `WatchedDependency.groupId()`, `WatchedDependency.artifactId()`:
  the accessors that made a constant an artifact. Nothing outside the enum called them once the
  message began rendering `ObservedVersion.coordinate()`. For the sweep, grep the qualified forms;
  bare `coordinate()` is a common and unrelated name across the tree (`SchemaCoordinate`, plan rows).
- `AbstractRewriteMojo.versionsOf`, replaced by `observedVersionsOf` and `referenceVersionsOf`. Its
  javadoc claim that "Maven has already mediated, so a coordinate appears once" is the sentence this
  item falsified, and it is gone rather than reworded in place.

