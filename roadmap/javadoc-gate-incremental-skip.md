---
id: R568
title: "Javadoc reference gate skips silently on rebuilds: the plugin up-to-date check compares options and file list, not source content"
status: Backlog
bucket: bug
priority: 5
theme: tooling
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# Javadoc reference gate skips silently on rebuilds: the plugin up-to-date check compares options and file list, not source content

The `check-link-references` execution in the root pom does not re-check a module whose javadoc
already ran, because maven-javadoc-plugin's incremental check keys on the javadoc *invocation*
rather than on the sources. It writes `target/maven-javadoc-plugin-stale-data.txt`, whose content
is the option strings plus the list of source file paths and nothing else: no mtimes, no digests.
On the next run it recomputes that string and, if it matches, logs `Skipping javadoc generation,
everything is up to date.` and reports success without invoking javadoc. Editing the body of an
existing file changes neither the options nor the file list, so a dangling `{@link}` added to a
file that was already there is skipped.

Found while implementing the maven-archiver override for the out-of-range `SOURCE_DATE_EPOCH`
failure. A planted `{@link}` to a nonexistent type in
`no.sikt.graphitron.javapoet.TypeVariableName` passed the gate on a warm `target/` and failed it
after deleting the stale-data file, which is what makes the mechanism visible.

Severity is bounded by where the gate actually gates: CI checks out fresh, so `target/` is absent,
the stale-data file cannot match, and javadoc always runs. Trunk is therefore protected and no
dangling link has shipped through this. What the skip costs is the local loop: a contributor who
adds a bad link and reruns `mvn install -Plocal-db` sees green, and only learns otherwise from CI.
That is a false green on the exact signal the gate exists to give, which is worth closing.

Adding a source file or changing any javadoc option does invalidate the stale data, which is why
the skip is intermittent rather than permanent and why it survived undetected. One incidental
option is in play here: `-bottom` carries the `{currentYear}` substitution, so a run with
`SOURCE_DATE_EPOCH` exported and a run without it disagree on the options string and each
invalidates the other's stale data.

Directions worth weighing at Spec time, not a decision yet: configure `staleDataPath` to a
per-run location so the check can never match; use `forceRootLocale` or another always-changing
option (a hack, and it defeats the mechanism rather than fixing it); or drop the incremental
check for this execution if the plugin exposes a lever for that. Cost matters, since the gate
already forks javadoc per module and the incremental skip is presumably why warm local builds
are as fast as they are; a fix that makes every rebuild pay full javadoc across every module may
be worse than the false green. Measure before choosing.
