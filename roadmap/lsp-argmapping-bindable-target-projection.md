---
id: R854
title: "LSP argMapping completions and diagnostics answer the bindable-target question from the census with no per-directive reservation"
status: Backlog
bucket: architecture
priority: 3
theme: interface-union
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# LSP argMapping completions and diagnostics answer the bindable-target question from the census with no per-directive reservation

Two LSP surfaces answer the question "which Java parameter names may an `argMapping` entry target" from the `jvm_method_parameter` census with no type predicate and no per-directive reservation: `ArgMappingCompletions.leftCandidates` selects `PARAMETER_NAME` for the named class and method and offers the union across overload descriptors (its javadoc states the union is deliberate), and `Diagnostics.parameterNames` unions `parameterNames()` across `answers.overloads(...)`, so `judgeArgMappingJavaParam` accepts any name in that union. The build answers the same question from live reflection, and on the `@condition` path it reserves the `Table`-assignable slots (`ServiceCatalog.checkConditionOverrideTargets`): with `cond(Film film, FilmFilter kriterier)`, the LSP offers `film` as a left-hand target and accepts `argMapping: "film: ..."` without a diagnostic, while the build rejects it. Two producers of one fact, nothing binding them; the divergence is one name per method today and gets multiplied by the participant count once R675's overload admission lands (per-participant sets deliberately name their table slots differently, and every admitted table-slot name becomes reserved set-wide).

The census already carries `JVM_METHOD_PARAMETER.PARAMETER_TYPE` and `DECLARED_PARAMETER_TYPE`, so the filter is expressible without new capture. The design question is where the fact lives, not which query gets a predicate: the `argMapping` coordinate (`InputField("ExternalCodeReference", "argMapping")`, keyed by `Behavior.ArgMappingBinding`) is shared with `@service` and `@externalField`, and a `Table`-assignable parameter is a reserved slot only on the `@condition` path (`ArgCallEmitter`'s `ParamSource.Table` arm exists on the `@service` path precisely to fail loudly on a leaked table slot, not to reserve one). So the bindable-target set wants to be a derivation over the census keyed on `(class, method)`, with reservation a per-directive projection over it, rather than a blanket type filter patched into two queries at a shared coordinate; a third spelling of the rule is the failure mode to avoid. Split out of R675, whose build-side admission deliverable states the reservation invariant this item's surfaces must agree with.
