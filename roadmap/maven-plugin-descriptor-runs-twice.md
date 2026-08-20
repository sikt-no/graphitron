---
id: R767
title: "graphitron-maven-plugin writes its descriptor twice and runs its three ITs one at a time"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# graphitron-maven-plugin writes its descriptor twice and runs its three ITs one at a time

`graphitron-maven-plugin` takes 32.8 seconds, runs alone throughout, and uses about one core. Two of
its goals account for 18.6 s of that and neither needs to cost what it costs: `plugin:descriptor` runs
twice for the same output, and `maven-invoker-plugin` runs three independent integration projects
sequentially.

## The measurement

One 4 vCPU sandbox, warm repository, per-goal timings from
`mvn install -pl :graphitron-maven-plugin -Plocal-db -Dorg.slf4j.simpleLogger.showDateTime=true`.
Module total 32.7 s.

| Goal | Time |
|---|---|
| `plugin:descriptor` (`default-descriptor`) | 3.8 s |
| `plugin:descriptor` (`mojo-descriptor`) | 3.5 s |
| `surefire:test`, 112 tests | 7.4 s |
| `invoker:run`, 3 integration projects | 15.1 s |
| everything else | 2.9 s |

Its 15.5 s of summed test-class time against 32.7 s of wall clock puts the module at 0.5 on the
saturation measure, which is to say it spends half its time outside surefire and runs that half on one
thread. It also sits on the build's critical path, between `graphitron-lsp` and
`graphitron-sakila-example`, so its seconds are wall-clock seconds. This module and
`graphitron-sakila-example` are the reactor's only two under-saturated nodes; R763 takes the other one
and carries the measurement behind that claim.

## The descriptor runs twice

The `maven-plugin` packaging lifecycle binds `plugin:descriptor` to `process-classes` as
`default-descriptor`. The pom then declares a second execution, `mojo-descriptor`, for the same goal
with no `<phase>`, which attaches it to that goal's default phase, which is the same `process-classes`.
Both run, and the timings show both paying full price: 3.8 s then 3.5 s.

A Spec pass should establish whether the explicit execution predates something before deleting it. The
`goalPrefix` configuration sits at plugin level and applies to both, so it is not the reason. If there
is no reason, this is 3.5 s of the critical path for a second copy of a file that was just written.

## The three ITs run sequentially

`maven-invoker-plugin` runs `basic-generate`, `dependency-version-lag` and `missing-schema-inputs`, each
forking a Maven build of its own, one after another, 15.1 s in total. The plugin has a
`parallelThreads` setting.

The reason to be careful rather than to just set it: the pom deliberately pins
`graphitron.store.directory` under `${project.build.directory}/it-store`, and its comment calls the
result "a free extra exercise of the multi-graph store", three ITs sharing one store under three
distinct artifactIds. Running them concurrently exercises something else again, three JVMs writing one
store at once. Whether that is a stronger test or a flake generator is the question the item exists to
answer, and the answer decides whether the 15.1 s is available.

## What a Spec pass has to settle

* **Whether `mojo-descriptor` has a reason.** Check the history before deleting: an execution that
  duplicates a lifecycle binding is usually a leftover from a packaging change, but "usually" is not
  established.
* **Whether the three ITs can share one store concurrently.** If they can, say so as a claim the ITs
  now make. If they cannot, the alternatives are a store directory per IT, which loses the multi-graph
  exercise the comment values, or leaving the ITs sequential and taking only the descriptor second.
* **Whether this module's figures are trustworthy enough to gate on.** It measured 30.8 s to 46.1 s
  across five runs of identical work in an earlier pass, because its ITs fork Maven builds whose own
  timing is noisy. Any claim about a change here needs repeated runs, and the item should say how many.

## How to re-measure

```bash
mvn install -pl :graphitron-maven-plugin -Plocal-db \
    -Dorg.slf4j.simpleLogger.showDateTime=true \
    -Dorg.slf4j.simpleLogger.dateTimeFormat="HH:mm:ss.SSS"
# Consecutive goal stamps give each goal's cost. Repeat at least three times before
# trusting a delta under five seconds; this module is the noisiest in the reactor.
```
