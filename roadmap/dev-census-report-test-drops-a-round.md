---
id: R1008
title: "DevMojoTest sometimes sees one classpath-census report for two generator rounds"
status: Backlog
bucket: bug
priority: 3
theme: testing
depends-on: []
created: 2026-10-09
last-updated: 2026-10-09
---

# DevMojoTest sometimes sees one classpath-census report for two generator rounds

## Goal

`DevMojoTest.runGeneratorPass_reportsWhatTheClasspathCensusCost` in `graphitron-maven-plugin`
answers the same on every run of an unchanged tree. Today it intermittently fails a full
`mvn install -Plocal-db` and passes on a resume of the same tree, so a red run cannot be told apart
from a regression in the census wiring it exists to pin, and a reviewer has to re-run the module to
learn nothing.

## What is known

The case drives `DevMojo.runGeneratorPass` twice through the malformed-schema arm and expects two
`classpath census:` info lines, one per round, read back from a `CapturingLog`. On 2026-10-09 it
failed one full `mvnd install -Plocal-db` with:

```
[every round says what the census cost, not only the first]
Expected size: 2 but was: 1 in:
["graphitron:dev: classpath census: 0 classes in 0 ms, nothing re-read"]
```

A resume from `graphitron-maven-plugin` on the same tree passed all 36 `DevMojoTest` cases minutes
later. The R964 Done record in `roadmap/changelog.md` already noted the same case flaking at that
item's pickup and left it untracked; this is the second sighting.

The test's classpath is empty (0 classes), so both rounds print the identical line, and the one
line seen does not say which round went silent. The census reports from inside
`ClasspathCensus.read`, through the sink `DevMojo.generatorFor` registers, and
`runGeneratorPass` calls `captureModel` before `generatorFor`, so a round that left the pass before
reaching `generatorFor` would log no census line. Where to start is whether one round took a
different arm than the malformed-schema one (the log's `errors` list from the failing run would
say), and what in that path depends on timing or on what ran beside it. R764 (`graphitron-model`'s
`junit-platform.properties` reaching this module's test classpath and making its classes run
concurrently) is the nearest piece of shared context and may be how a neighbour gets in.

Found while running the verification build for the `film_actor` rolled-back-seed fix in
`graphitron-sakila-example`, which touches nothing in this module.
