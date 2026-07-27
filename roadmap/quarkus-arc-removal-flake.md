---
id: R550
title: "Pin around the Quarkus ArC unused-bean removal flake"
status: Backlog
bucket: bug
priority: 3
theme: testing
depends-on: []
created: 2026-07-27
last-updated: 2026-07-27
---

# Pin around the Quarkus ArC unused-bean removal flake

> The `quarkus:build` goal on `graphitron-sakila-example` intermittently fails CI with a
> `NullPointerException` inside Quarkus ArC's unused-bean removal pass. It is not reproducible
> locally, it is not caused by anything in our bean graph, and it is not fixed by upgrading
> Quarkus. Because the goal runs in the `build` job that `docs-build` and `docs-deploy` depend
> on, a hit takes the documentation site down with it: a green fix can land on trunk and never
> reach `graphitron.sikt.no`. Disable the removal pass for this module so the code path cannot
> run.

---

## Observed failure

One occurrence so far, on a trunk push whose diff was two `.adoc` files, one test class, and two
roadmap markdown files. All 655 tests in the module passed; the failure came afterward, in
`quarkus:build`:

```
Failed to execute goal io.quarkus:quarkus-maven-plugin:3.34.5:build (default)
  on project graphitron-sakila-example: Failed to build quarkus application:
[error]: Build step io.quarkus.arc.deployment.ArcProcessor#validate threw an exception:
  java.lang.NullPointerException: Cannot invoke
  "io.quarkus.arc.processor.InjectionPointInfo.isProgrammaticLookup()"
  because "injectionPoint" is null
    at io.quarkus.arc.processor.UnusedBeans.findRemovableBeans(UnusedBeans.java:44)
    at io.quarkus.arc.processor.BeanDeployment.removeUnusedBeans(BeanDeployment.java:501)
    at io.quarkus.arc.processor.BeanDeployment.removeUnusedComponents(BeanDeployment.java:410)
    at io.quarkus.arc.processor.BeanDeployment.init(BeanDeployment.java:357)
```

A re-run of the same commit went green with no code change.

## What was established

- **Not ours.** Quarkus augmentation indexes main classes and dependencies, not `src/test/java`.
  The module's entire bean graph is `SakilaGraphitronApplication` plus `graphitron-jakarta-rest`'s
  `GraphqlResource`; the generated sources carry zero CDI annotations, so generated output cannot
  contribute a bean or an injection point. The same graph had been green on every preceding trunk
  run.
- **Not reproducible locally.** Four green runs on the exact failing commit: three of
  `mvn package -pl :graphitron-sakila-example -Plocal-db -DskipTests`, plus one of
  `mvn verify -Plocal-db --batch-mode -T 1C`, which is byte-for-byte the CI command. CI's
  contended runner is the only place it has appeared.
- **Not fixed by a version bump.** `UnusedBeans.java` is byte-identical between 3.34.5 (ours) and
  3.38.0 (latest at time of writing), verified by diffing the `io.quarkus.arc:arc-processor`
  sources jars. `BeanDeployment.java` did change, but still allocates `injectionPoints` as a
  `CopyOnWriteArrayList` and still passes it straight to `UnusedBeans.findRemovableBeans`. Bumping
  Quarkus may be worth doing on its own merits; it will not address this.
- **The dereference site is single-threaded.** `UnusedBeans.findRemovableBeans` iterates
  `injectionPoints` in a plain `for` loop with no null tolerance, so the null was already in the
  list when the method was called. The bug is in whoever added it, not in the loop.

## Hypothesis, not yet proven

`BeanDeployment.injectionPoints` is a `CopyOnWriteArrayList` filled by repeated `addAll(...)`
calls from collections assembled across Quarkus's build-step threads, including the synthetic
injection points contributed by bean-registration extensions.
`CopyOnWriteArrayList.addAll(Collection)` snapshots its argument through `toArray()`, and
`toArray()` on a plain `ArrayList` being mutated concurrently can return an array with trailing
nulls, which the COW list then stores verbatim. That would produce a null with no offending bean
to point at, invisible on an idle machine, surfacing under load. Consistent with every symptom,
but confirming it needs the failure captured under a debugger, which a green environment cannot
provide. The pin below does not depend on the hypothesis being right.

## Proposed fix

Set `quarkus.arc.remove-unused-beans=false` in
`graphitron-sakila-example/src/main/resources/application.properties`.

`BeanDeployment.init` guards the whole removal pass behind `if (removeUnusedBeans)`, and that
field is the builder-level reflection of this property, so with it off `removeUnusedComponents`
never runs, `findRemovableBeans` is never called, and the null cannot be dereferenced. Dead-bean
removal is a production startup-footprint optimisation; this module is an example app whose
purpose is to compile emitted sources at release 17 and exercise them against a real database, so
it gains nothing from the pass.

Carry a comment naming the mechanism and the reason, so a later reader does not delete the
property as unexplained cruft.

## Open questions for the Spec pass

- Property or POM? The property is closer to where a reader looks; a
  `<quarkus.arc.remove-unused-beans>` system property in the module's plugin configuration keeps
  it out of the runtime config that the tutorial narrates. Pick one and say why.
- Does anything else in the reactor run `quarkus:build`? If a second module ever does, the pin
  belongs somewhere shared rather than duplicated.
- Worth reporting upstream? The COW-plus-`toArray` mechanism above is a genuine defect if
  confirmed, and a report costs little. Nobody has searched the Quarkus tracker yet; GitHub
  access in the session that found this was scoped to `sikt-no/graphitron`.

## Out of scope

- Bumping Quarkus. Established above as not a fix for this. If a bump is wanted, it is its own
  item with its own regression surface.
- Hardening our own code against the null. The list is Quarkus-internal; we have no seam.
- Any change to the CI job layout. Making `docs-deploy` independent of `build` would decouple the
  documentation site from unrelated module failures, which is arguably worth doing, but it is a
  separate question from this flake and should not ride along.

