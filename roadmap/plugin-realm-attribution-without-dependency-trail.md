---
id: R1013
title: "The plugin realm refusal names the declared entry without Maven dependency trail"
status: Ready
bucket: bug
priority: 1
theme: tooling
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# The plugin realm refusal names the declared entry without Maven dependency trail

## Goal

When a consumer's `<plugin><dependencies>` block for graphitron-maven-plugin puts a second jOOQ, or a
jOOQ at another version, on the plugin's classloader, the build failure names the jar and the pom entry
that brought it on every Maven version, including 3.10. Today, on Maven 3.10, it says the jar came from
"one of the entries" and gives a vaguer remedy. Trunk CI goes green again: the
`plugin-block-second-jooq` and `plugin-block-mediated-jooq` invoker ITs have failed on every trunk run
since R951 landed, because the CI runner now has Maven 3.10.0.

The one case that stays vague is a consumer with two or more plugin-block entries whose stray jOOQ
arrives transitively, on a Maven that does not record dependency trails. The message then lists the
candidate entries, which is true and actionable.

## What happens today

The *plugin realm* is the classloader Maven builds for graphitron-maven-plugin: graphitron's own
dependencies plus whatever the consumer lists under `<plugin><dependencies>`. `PluginRealm.refusal`
fails every goal when that realm holds more than one jOOQ, or one jOOQ that is not the version
graphitron was compiled against. The decision is sound on every Maven; only the attribution (which pom
entry to blame) degrades.

`PluginRealm.attribution` and `PluginRealm.remedy` pick their wording from `PluginRealm.entryFor`, which
reads `Row.broughtInBy()`: the second element of the artifact's *dependency trail*, the path Maven
records from the plugin down to each resolved jar, so element two is the plugin's direct dependency that
pulled the jar in. When that names one of the consumer's plugin-block entries the message is exact,
`(declared under <plugin><dependencies> for graphitron-maven-plugin)` with
`Remove <coordinate> from the plugin's <dependencies>`. Otherwise it lists every entry,
`(brought in by one of the entries declared under ...: <entries>)` with
`Move the entry that brings it ...`.

The trail is a Maven core detail, and it changed between minor versions:

* Maven 3.9.11: `DefaultMavenPluginManager.toMavenArtifacts` builds the plugin artifacts through
  `RepositoryUtils.toArtifacts(artifacts, nodes, trail, filter)`, which sets a full trail on each.
  The ITs pass, locally and in R951's Done-gate build.
* Maven 3.10.0: the same method maps each node of the `DependencyResult` through
  `RepositoryUtils.toArtifact(Dependency)`, which sets scope and optional and no trail.
  `getDependencyTrail()` is null for every plugin artifact, `broughtInBy()` is empty, and both ITs
  get the fallback wording their `verify.groovy` rejects.

Both were read from the maven-core jars' bytecode. `rewrite-build.yml` runs the runner image's `mvn`
unpinned, and the GitHub Ubuntu 24.04 image lists Maven 3.10.0.

The sibling reader of trails already tolerates this: `AbstractRewriteMojo.isDirect` reads the trail
where resolution populated it and otherwise joins against the module's declared dependencies, which is
why the `transitive-not-nameable` IT still passes on 3.10. This item gives `PluginRealm` the same
stance, not the same join: the trail where Maven supplies it, and facts graphitron holds itself where it
does not. (`isDirect` joins on groupId, artifactId, type and classifier; the realm's entries are matched
on groupId and artifactId, as `consumerAdded` already is.)

## Implementation

All in `graphitron-maven-plugin`.

`PluginRealm.entryFor` resolves the entry in three steps, first answer wins:

1. **The jar is an entry.** A row whose own `groupId:artifactId` is in `consumerAdded` is that entry;
   the consumer named it. Needs no trail. Covers both failing ITs: `org.example.it:second-jooq`
   declared and stray, `org.jooq:jooq` declared and mediated over graphitron's own.
2. **The trail names an entry.** Today's `row.broughtInBy().filter(consumerAdded::contains)`, unchanged.
   On a Maven that records trails this traces a transitive stray to its entry, as now.
3. **Only one entry could have.** When neither step answers and the consumer declared exactly one
   plugin-block entry, that entry. This is the existing fallback narrowed, not a new guess: today the
   message already lists the consumer's entries as the candidates, and a candidate list of one is a
   named entry. The premise underneath is that graphitron's realm with an empty plugin block passes the
   check, which every invoker IT without a plugin block (`basic-generate` among them) enforces. So a
   realm that fails it was made to fail by the consumer's entries, whether the stray jar sits under one
   of them or an entry's transitive dependency won Maven's version mediation over one of graphitron's
   own. With one entry, it is the cause, whatever path the trail (if any) records.

```java
private Optional<String> entryFor(Row row) {
    if (row == null) {
        return Optional.empty();
    }
    if (consumerAdded.contains(row.coordinate())) {
        return Optional.of(row.coordinate());
    }
    var traced = row.broughtInBy().filter(consumerAdded::contains);
    if (traced.isPresent()) {
        return traced;
    }
    return consumerAdded.size() == 1 ? Optional.of(consumerAdded.get(0)) : Optional.empty();
}
```

`attribution` and `remedy` are unchanged: they already render "declared" when the entry is the jar
itself, "brought in by X" when it is another entry, and the candidate list when there is none. With two
or more entries step 3 never fires, so those messages keep their current wording on every Maven.

Prose that states the trail as the only source moves with the code: the javadoc on `entryFor`
("the entry the trail names") and on `Row` (say the trail may be empty, as on Maven 3.10, and that
`entryFor` does not depend on it).

The user manual's promise in `docs/manual/reference/mojo-configuration.adoc` (the realm sentence ending
"naming the jar and the entry") becomes true in the cases above and stays slightly over-strong in the
multi-entry transitive case. Soften it to name the jar and the entry that brought it, or the candidate
entries when Maven does not record which one did.

No CI change. The runner's Maven stays unpinned, so CI keeps exercising the no-trail path on 3.10 while
local builds (Maven 3.9.11) exercise the trail path.

## Tests

`PluginRealmTest` (unit tier, hand-built rows), alongside the existing trail-bearing cases:

* **Two-root arm, no trail, jar is the entry.** A foreign row with a null trail whose coordinate is the
  sole plugin-block entry: exact "declared under" attribution and the "Remove ..." remedy. The
  no-trail twin of `plugin-block-second-jooq`.
* **Version arm, no trail, jar is the entry.** `oneJarAtAnotherVersionNamesBothVersionsAndTheEntryToRemove`
  with a null trail produces the same full message. The no-trail twin of `plugin-block-mediated-jooq`.
* **No trail, one entry, jar is not the entry.** A foreign `org.jooq.pro:jooq` row with a null trail and
  `consumerAdded = [no.fellesstudentsystem:sis-service]`: the same message
  `aSecondJarNamesBothAndThePluginBlockEntryItsTrailLeadsTo` pins with a trail.
* **No trail, two entries.** `aSecondJarWithNoTrailListsTheConsumerAddedEntriesAsCandidates`, unchanged,
  pins that the candidate list survives where nothing can be deduced.
* **One entry, trail through graphitron's own dependency.** A foreign `org.jooq:jooq` row whose trail
  runs through `no.sikt:graphitron` (an entry's transitive jOOQ won mediation) and one plugin-block
  entry: the entry is named. Pins that step 3 follows the premise rather than the trail's path.

The tests do not pin an older gap in the two-root arm's fallback (`foreign = jooqRoots.subList(1, ...)`
when no root is recognisably graphitron's): the first root, itself foreign, goes unreported. That is
out of this item's scope; new assertions must not lock in its omission.

Invoker ITs: `plugin-block-second-jooq` and `plugin-block-mediated-jooq` are unchanged and pass on both
3.9.11 and 3.10.0. New IT `plugin-block-transitive-jooq`: the plugin block declares one entry,
`org.example.it:second-jooq-carrier:1.0`, a jar from a new setup project that depends on
`org.example.it:second-jooq:1.0`. `verify.groovy` asserts
`also holds org.example.it:second-jooq:1.0 (brought in by org.example.it:second-jooq-carrier, declared under <plugin><dependencies> for graphitron-maven-plugin)`
and the `Move org.example.it:second-jooq-carrier ...` remedy, plus the store-not-opened check the
siblings make. The same assertion passes by the trail on 3.9.11 and by deduction on 3.10.0, so this IT
is the end-to-end pin on the real consumer shape (one service jar in the plugin block that carries an
edition jOOQ) on whichever Maven runs it. The carrier's setup project installs after
`setup-second-jooq`, since its compile resolves that jar; order it explicitly through invoker's
ordering support rather than relying on directory-name order.

Acceptance: CI's `rewrite-build.yml` green on trunk under Maven 3.10.0, and the local verification build
green under 3.9.11.

## Other solutions we've considered

* **Pin Maven 3.9.11 in CI.** Turns trunk green and leaves consumers on 3.10 with the degraded message;
  it hides the next Maven-internal change from CI rather than tolerating it.
* **Re-resolve each plugin-block entry's dependency graph on the refusal path.** Injecting Maven's
  `RepositorySystem` and collecting each entry's tree would recover the multi-entry transitive case on
  any Maven. It adds resolver wiring, a repository session and possibly network access to an error path
  whose output is one sentence, and it computes a second graph that need not match the realm Maven
  actually built (mediation runs over the whole plugin graph, not per entry). The residual case already
  gets a true message listing a handful of candidates. Worth revisiting only if a consumer reports it.
