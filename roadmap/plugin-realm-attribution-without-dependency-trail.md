---
id: R1013
title: "The plugin realm refusal names the declared entry without Maven dependency trail"
status: Backlog
bucket: bug
priority: 1
theme: tooling
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# The plugin realm refusal names the declared entry without Maven dependency trail

## Goal

When a consumer's `<plugin><dependencies>` block declares a second jOOQ for graphitron-maven-plugin, the
refusal names that entry and says to remove it on every Maven version, including 3.10, where today it
falls back to "one of the entries" and a vaguer remedy. Trunk CI goes green again: the
`plugin-block-second-jooq` and `plugin-block-mediated-jooq` invoker ITs have failed on every trunk
run since R951 landed, because the runner now has Maven 3.10.0.

## What happens today

`PluginRealm.attribution` and `PluginRealm.remedy` pick their wording from `entryFor`, which reads
`Row.broughtInBy()`: the second element of the artifact's Maven dependency trail, that is, the
plugin's direct dependency that pulled the jar in. When that names one of the consumer's
plugin-block entries the message is exact, `(declared under <plugin><dependencies> for
graphitron-maven-plugin)` with `Remove <coordinate> from the plugin's <dependencies>`. Otherwise it
lists every entry, `(brought in by one of the entries declared under ...: <entries>)` with `Move the
entry that brings it ...`.

The trail is a Maven core detail, and it changed between minor versions:

* Maven 3.9.11: `DefaultMavenPluginManager.toMavenArtifacts` builds the plugin artifacts through
  `RepositoryUtils.toArtifacts(artifacts, nodes, trail, filter)`, which sets a full trail on each.
  The ITs pass, locally and in R951's Done-gate build.
* Maven 3.10.0: the same method maps each node of the `DependencyResult` through
  `RepositoryUtils.toArtifact(Dependency)`, which sets scope and optional and no trail.
  `getDependencyTrail()` is null for every plugin artifact, `broughtInBy()` is empty, and both ITs
  get the fallback wording their `verify.groovy` rejects.

Both were read from the maven-core jars' bytecode. `rewrite-build.yml` runs the runner image's `mvn`
unpinned, and the GitHub Ubuntu 24.04 image lists Maven 3.10.0. The refusal itself still fires on
3.10; only the attribution degrades, so consumers on 3.10 see the vaguer message too.

## Proposed fix

A stray jar whose own `groupId:artifactId` is one of the consumer's plugin-block entries is that
entry, with no trail needed. `entryFor` checks that first and consults the trail only for a jar the
consumer did not name directly:

```java
if (consumerAdded.contains(row.coordinate())) {
    return Optional.of(row.coordinate());
}
return row.broughtInBy().filter(consumerAdded::contains);
```

That covers both ITs (`org.example.it:second-jooq` declared and stray; `org.jooq:jooq` declared and
mediated over graphitron's own) on any Maven. A jOOQ pulled in transitively by some other entry
still cannot be traced on 3.10, and keeps the "one of the entries" wording, which stays true.
Recovering that case would mean reading the resolved dependency graph rather than the artifacts'
trails; the spec decides whether that is worth its code.

Tests: a `PluginRealmTest` case with rows carrying no trail, for both refusal arms, alongside the
existing trail-bearing cases. The invoker ITs are unchanged and pass on 3.10.

Not chosen: pinning Maven 3.9.11 in CI. It turns trunk green and leaves consumers on 3.10 with the
degraded message, and hides the next Maven-internal change from CI rather than tolerating it.
