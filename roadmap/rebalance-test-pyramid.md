---
id: R25
title: "Measure the test pyramid: JaCoCo coverage instrumentation"
status: Backlog
bucket: architecture
priority: 9
theme: testing
depends-on: []
last-updated: 2026-07-14
---

# Measure the test pyramid: JaCoCo coverage instrumentation

Re-specced 2026-07-14. The original one-sentence item ("shift new test investment from per-variant structural tests toward SDL-to-classification-to-emission pipeline tests") has become doctrine since filing: the tier guide (`docs/architecture/how-to/testing.adoc`, shipped by R29) states "pipeline beats unit: per-variant structural tests are bookkeeping", and R281's `ClassifiedCorpus` is migrating truth-table enum cases (`GraphitronSchemaBuilderTest`) onto corpus fixtures. What the item lacked was measurement: nothing in the build reports line/branch coverage, so pyramid claims are unquantified. This item wires JaCoCo in so the balance is measured, not asserted.

## Baseline (measured 2026-07-14, ad hoc, no pom changes)

```bash
mvn org.jacoco:jacoco-maven-plugin:0.8.15:prepare-agent install \
    org.jacoco:jacoco-maven-plugin:0.8.15:report -Plocal-db '-P!docs' -Dleaf-coverage.skip
```

| Module | Line | Branch | Method | Meaning |
|---|---|---|---|---|
| `graphitron` | 84.5% | 72.7% | 89.4% | generator source, from its own unit+pipeline tiers |
| `graphitron-sakila-example` | 76.9% | 49.5% | 63.8% | *generated* code, from the execution tier |
| `roadmap-tool` | 63.5% | 52.5% | 69.6% | build tooling |

Weakest generator-source spots are the emitters: `generators` is the lowest-covered package (70.8% line over ~7,300 lines), with `JooqRecordInstantiationEmitter` at 40.7%, `FetcherEmitter` 50.2%, `ArgCallEmitter` 50.6%, and `TypeFetcherGenerator` 63.8% over ~3,000 lines. The `graphitron-sakila-example` row is a measurement nothing else provides: how much of the emitted resolver code the execution specs exercise (49.5% branch suggests substantial generated defensive branching never runs).

## Deliverables

1. Wire `jacoco-maven-plugin` (0.8.15, pinned in the root pom properties) into the reactor: `prepare-agent` + per-module `report`, optionally `report-aggregate`.
2. Fix agent-dropping surefire configs: `graphitron-mcp`, `graphitron-lsp`, and `graphitron-maven-plugin` hard-set `<argLine>--enable-native-access=ALL-UNNAMED</argLine>` without the `@{argLine}` placeholder, so the JaCoCo agent silently drops there; prepend `@{argLine} `. `graphitron-javapoet` produced no exec data in the baseline run despite correct-looking wiring (`@{argLine}` present, empty `<argLine/>` property declared); diagnose while wiring.
3. Per-tier coverage split. The tier tags already exist (`unit` / `pipeline` / `compilation` / `execution`, enforced by `TierAnnotationEnforcementTest`), so surefire `groups` runs can measure what each tier contributes to generator-source coverage. This is the number that makes the pyramid claim testable: if pipeline-tier-only coverage approaches the combined figure, unit-tier structural tests are confirmed as redundant bookkeeping; where it does not, the gap names the code only unit tests reach.
4. Reporting surface: decide at Spec time between a doc-site page alongside the leaf-coverage report (regenerated per trunk push by `rewrite-build.yml`, same pattern as `roadmap/inference-axis-coverage.adoc`) and a CI artifact. Coverage must not run on every local build; gate it behind a profile so the default `mvn install -Plocal-db` cost is unchanged.
5. No threshold or ratchet gates initially. Evidence first; a ratchet is a follow-up decision once a few windows of data exist.

## Non-goals

* Instrumenting the Maven JVM to capture the maven-plugin's generate executions in downstream modules (the compile-spec path exercises generator code inside the Maven process, invisible to surefire-attached JaCoCo). Possible later via `MAVEN_OPTS` javaagent; out of scope here.
* Mutation testing (pitest); separate item if ever wanted.
* Acting on the coverage numbers themselves (e.g. the emitter gaps above); this item builds the instrument, follow-ups spend the signal.

## Relation to existing machinery

Complementary to the leaf-coverage report (`roadmap-tool leaf-coverage`, rendered at `roadmap/inference-axis-coverage.adoc`): that measures which classification-taxonomy leaves the corpus demonstrates; JaCoCo measures which source lines/branches execute. Neither subsumes the other.
