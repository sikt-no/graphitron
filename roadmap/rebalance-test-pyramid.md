---
id: R25
title: "Measure the test pyramid: JaCoCo coverage instrumentation"
status: Spec
bucket: architecture
priority: 9
theme: testing
depends-on: []
last-updated: 2026-07-31
---

# Measure the test pyramid: JaCoCo coverage instrumentation

The original one-sentence item ("shift new test investment from per-variant structural tests toward SDL-to-classification-to-emission pipeline tests") has become doctrine since filing: the tier guide (`docs/architecture/how-to/testing.adoc`) states "pipeline beats unit: per-variant structural tests are bookkeeping", and R281's `ClassifiedCorpus` took over the truth table's pure-verdict cases, leaving `GraphitronSchemaBuilderTest` its slot-asserting, rejection, and input-side rows. What the item lacked was measurement: nothing in the build reports line/branch coverage, so pyramid claims are unquantified. This item wires JaCoCo in so the balance is measured, not asserted.

It builds an instrument and publishes its readings. It does not act on them, and it adds no threshold or ratchet gate.

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

Treat these numbers as a shape, not a pin. They were taken by hand on one container; the point of this item is that nobody has to take them by hand again.

## Design decisions

Recorded here because deliverable 4 of the original body deferred them to Spec time.

### 1. Opt-in profile named `coverage`, plugin config in the parent pom

`-Pcoverage` with no `<activation>` block, so it is off unless asked for. The plugin config lives in the parent pom's profile `<build><plugins>` and every module inherits it, exactly as the `leaf-coverage` profile does. `<version.org.jacoco>0.8.15</version.org.jacoco>` joins the root `<properties>` and the plugin joins `<pluginManagement>`.

The inverse activation the `leaf-coverage` profile uses (property negation on `!leaf-coverage.skip`, chosen because `activeByDefault` silently loses to any other explicitly-activated profile) is deliberately *not* copied. Coverage costs test-time agent overhead on every fork, and the original body's constraint stands: the default `mvn install -Plocal-db` cost must be unchanged.

This honours R117's programme principle that every measurement dimension is opt-in and never costs the production-codegen path. It stays a *separate* toggle from `-Pleaf-coverage` rather than folding into one measurement profile: the leaf traces are a cheap JSONL append, JaCoCo is a bytecode-instrumenting agent, and one flag that means both makes the cheap thing pay the expensive thing's price. If R133 flips `leaf-coverage` to opt-in and a single `-Pmeasure` umbrella becomes attractive, converging the two is a follow-up with both costs already known.

### 2. `append=false` on `prepare-agent`

JaCoCo's `append` defaults to `true`, so a rerun on an uncleaned `target/` accumulates exec data across runs and reports coverage from code the current run never executed. This is the same footgun class the `leaf-coverage` profile spends an antrun `truncate-leaf-coverage-trace` execution on, and JaCoCo has a parameter for it, so no antrun is needed.

Constraint this imposes: it is safe only while each module runs exactly one test-executing plugin execution. No module binds failsafe today (it appears in the root pom only, in `<pluginManagement>` and in the leaf-coverage profile's configuration block, with no execution binding its goals anywhere) and no module configures `forkCount`, so the reactor satisfies this. A module that later adds failsafe needs its own `prepare-agent-integration` execution with a distinct `destFile`.

That constraint gets an enforcer, not a comment. Its failure mode is strictly worse than the one decision 8 gates: a dropped agent yields a suspicious `0%`, while a second execution overwriting the first yields a plausible partial figure with no tell at all. Both rules live in one pom walker (see `CoverageAgentWiringCheck` in Implementation), so the invariant is unrepresentable rather than documented.

### 3. `destFile`, `dataFile` and `outputDirectory` stay at their defaults

The profile must not hard-set them. Each is a `@Parameter(property = ...)` on the JaCoCo mojos, and pom-level configuration wins over `-D`, which would kill the per-tier recipe in decision 6 before it is written. Leaving them defaulted keeps `-Djacoco.destFile`, `-Djacoco.dataFile` and `-Djacoco.outputDirectory` available on the command line.

### 4. Reporting surface: a doc-site page, generated by roadmap-tool

`roadmap/source-coverage.adoc`, a sibling of `roadmap/inference-axis-coverage.adoc`, published to the docs site on every trunk push through the artifact chain R140 already built.

The alternative, publishing JaCoCo's own HTML report as a site directory, is rejected: it dumps several hundred generated files into Pages, and it cannot express the one view this item exists to produce (module and package coverage side by side with the per-tier split from decision 6, in one table). JaCoCo's `report` goal emits `target/site/jacoco/jacoco.csv` alongside the HTML, which is the machine-readable input a generated page needs.

Verified on `roadmap-tool` in this session: `jacoco.csv` carries `GROUP,PACKAGE,CLASS` plus missed/covered pairs for instruction, branch, line, complexity and method, and `GROUP` resolves to the module coordinate (`no.sikt:graphitron-roadmap-tool`, from this reactor's `<name>${project.groupId}:${project.artifactId}</name>` convention). Module, package and class attribution therefore need no path parsing.

`report-aggregate` is not used. It would need a new aggregator module depending on every measured module, which `check-module-enumeration` would then require in both `CLAUDE.md` and `docs/architecture/reference/modules.adoc`: a module in the reactor to hold a Maven plugin binding. Aggregating the per-module CSVs in the report tool costs a `GROUP BY` instead.

### 5. CI activation folds into the existing build step

`-Pcoverage` is added to the `build` job's existing `mvn verify -Plocal-db --batch-mode -T 1C` (whose `verify` the Implementation flips to `install` so the tier steps in decision 6 can resolve this run's reactor artifacts), so coverage costs agent overhead on the build CI already runs, not a second reactor build on top of it. The regen, upload and download steps are trunk-gated, mirroring the leaf-coverage chain exactly.

PR builds therefore pay agent overhead without publishing anything, and that is the point rather than a cost to apologise for: the coverage wiring is continuously exercised by the gate. An `argLine` regression, an agent incompatibility, or a module that starts binding failsafe fails on the PR that introduced it instead of on trunk. This is the same reasoning the workflow's existing comment gives for CI using the same `-Plocal-db` code path so no second profile drifts, and it is why the obvious third option, conditioning `-Pcoverage` on the trunk-push expression, is wrong: conditioning is exactly what would let the coverage path rot unnoticed.

Two cost notes. Per-module `target/jacoco.exec` files mean `-T 1C` module parallelism has nothing to contend over. But the workflow comment records `-T 1C` as an *enforced invariant* on parallel-test tolerance, and attaching an agent to every fork changes that run's timing profile; probably benign, and verification step 4 exists to confirm it once rather than assume it. The agent's wall-clock overhead on this reactor is currently unmeasured, so verification records the delta instead of the item asserting it.

### 6. The per-tier split runs inside `graphitron`, published from CI

Deliverable 3's premise needs one correction: the split is not four-armed. The reason is decision 4's per-module report architecture, not physics, and the distinction matters enough to state precisely, because there are **two different blind spots** here and it is tempting to collapse them into one.

- Generator code that runs in the **Maven JVM** during `graphitron:generate` is never instrumented at all. A surefire-attached agent cannot see it. This is the pre-existing non-goal.
- Generator code that runs in **another module's test JVM** *is* instrumented and *is* written into that module's exec data. `GeneratorDeterminismTest` invokes the generator in-process in `graphitron-sakila-example` (its pom says so at the `graphitron` test-scope dependency), so those executions are collected. They are then discarded at report time, because `report` analyses only the module's own `target/classes`.

So the second blind spot is a *choice*: recovering it means `report-aggregate` or a `report` pointed at additional class directories, both of which decision 4 rejected on cost grounds. Compilation-tier and execution-tier contribution to generator-source coverage is therefore near zero *as reported*, not near zero as executed. The published page states both blind spots in those terms; claiming "by construction" would be a claim a JaCoCo-literate reviewer could falsify in thirty seconds.

What remains is the comparison the pyramid question actually needs, `unit` versus `pipeline` inside `graphitron`:

```bash
# unit tier only
mvn test -pl :graphitron -Plocal-db -Pcoverage -Dgroups=unit \
    -Dleaf-coverage.skip -Djacoco.destFile=target/jacoco-unit.exec
mvn -pl :graphitron org.jacoco:jacoco-maven-plugin:0.8.15:report \
    -Djacoco.dataFile=target/jacoco-unit.exec \
    -Djacoco.outputDirectory=target/site/jacoco-unit

# pipeline tier only (same shape, -Dgroups=pipeline, jacoco-pipeline.*)
```

Relative `-D` paths resolve against the built module's basedir, so `target/...` lands under `graphitron/`. Both halves of that were checked in this session against the reactor as it stands: `-Dgroups=unit` selects 1631 tests in `graphitron`, and `-Djacoco.destFile=target/jacoco-unit.exec` produced a distinct 266 KB exec file at `graphitron/target/jacoco-unit.exec`. The recipe is transcribed from a run, not composed from documentation.

`-Dleaf-coverage.skip` is not optional. The leaf-coverage profile is active by default, and every tier run without the flag pays its `truncate-leaf-coverage-trace` execution at `process-test-resources` and then re-emits traces from only that tier's tests, leaving `graphitron/target/leaf-coverage.jsonl` holding a strict subset of what the full suite wrote. In CI that file feeds the trunk-gated `Regenerate leaf-coverage report` step, so a tier run without the flag silently strips every leaf the last-run tier does not exercise out of the published inference-axis report. The baseline command at the top of this item carries the same flag for the same reason. The flag deactivates the profile entirely (no truncation, no trace emission), which makes the tier runs trace-inert and their ordering relative to the leaf-coverage regen a non-issue; the CI ordering below still places them after it as belt and braces.

**This runs in CI, trunk-gated.** The first draft of this decision left it as an on-demand recipe on the assumption that two extra `graphitron` suite runs per trunk push were expensive. Measured in this session on a warm reactor: `-Dgroups=unit` is 35 s, `-Dgroups=pipeline` is 39 s, against 55 s for the module's whole suite. Both are `graphitron`-only, `test`-phase, with no Postgres, no jOOQ codegen and no example-module compile. So the two extra runs add about 74 s to a trunk push, roughly 1.3x the module's own test time and a small fraction of a reactor build that provisions Postgres and builds libtree-sitter from source. For that, the item's title stops being a promise the published page cannot keep: the tier balance is the one figure this item exists to produce, and leaving it out of the artefact while calling the item "Measure the test pyramid" is the incoherence a reviewer would rightly land on first.

Two trunk-gated steps in the `build` job then, after the `Regenerate leaf-coverage report` step and before the `Regenerate source-coverage report` step, each running its tier and its `report` invocation. `source-coverage` still renders tier columns conditionally on the `target/site/jacoco-<tier>/jacoco.csv` directories existing, so a contributor running one tier locally gets the same page shape with no extra flag, and a PR build (which skips the tier steps) renders the combined tables alone.

### 7. No `--verify` drift mode

Unlike `leaf-coverage --verify` and `directive-support --verify`, the generated page must not be drift-gated, and the reason is categorical rather than a matter of how often the gate would fire. Those two artefacts are pure functions of committed sources, so a stale file *is* drift and a `--verify` fixed point exists. A coverage percentage is a function of an executed run and its environment; there is no fixed point to compare against, so there is nothing a drift gate could mean. This is the materialized-view rule applied correctly, not an exemption from it.

It follows that there should be no committed data file either. `inference-axis-coverage.adoc`'s committed copy can at least be regenerated locally into its true value; a committed `source-coverage.adoc` would be a file in the tree that is permanently known-wrong and unverifiable by anything. `runRenderAdoc` already guards its report copy on `Files.exists`, so instead of a placeholder it synthesizes a short "no coverage data was collected in this build" stub into the staging directory when the file is absent. Local doc builds and PR previews get a page at the expected path, which is what R140's placeholder existed for, without the repo carrying a wrong one.

### 8. Broken instrument wiring becomes a build gate

Deliverable 2's bug is silent: a module whose surefire `<argLine>` omits `@{argLine}` drops the agent and reports 0%, which reads identically to "this module has no tests". A wrong number that looks like a real number is worse than a build failure, so this gets a mechanical guard rather than a comment, and it covers decision 2's sibling invariant in the same walker.

This does not contradict deliverable 5's "no threshold or ratchet gates initially". That bars gates that *judge the numbers*: thresholds, `jacoco:check`, ratchets. This one guards the instrument's own correctness, which is the precondition for the numbers meaning anything. The Non-goals below keep the coverage-judging gates out; this one stays.

Two details the implementer must get right, because the enforcer's scope is deliberately wider than the invariant's. The check runs on an ordinary `mvn install -Plocal-db`, failing a shape that is only wrong under `-Pcoverage`; that is correct (a profile-conditional check would never fire for anyone), but it means the message has to carry the *why* or a contributor who has never run `-Pcoverage` hits an inexplicable break. And the message must name the consequence, that the agent is dropped and the module reports a false `0%`, not just the syntax it wants.

### 9. One tier vocabulary, and columns that admit they are slices

Two problems with the naive version of the tier split, both of which would mislead exactly the audience the page is for.

*The vocabulary would fork.* `LeafCoverageReport` already owns a tier vocabulary in this same tool: `TIER_ORDER` over `unit < pipeline < compilation < execution`, with `cross-cutting` deliberately kept off the ordering as a separate flag. A second two-arm list in `SourceCoverageReport`, derived from the same `@UnitTier` / `@PipelineTier` annotations, with nothing binding the two, is a derived fact maintained apart from its source. Hoist the vocabulary to one place both reports read, preserving the `cross-cutting`-off-the-ordering treatment, and derive both the recipe's tier list and the rendered columns from it.

*The columns are not a partition.* `-Dgroups=unit` plus `-Dgroups=pipeline` does not cover `graphitron`'s suite: `@Tag("cross-cutting")` classes fall into neither, and any tag added later falls through both silently. A reader seeing two percentage columns headed `unit` and `pipeline` next to a combined column will read them as a decomposition, and they are not. The page prose says so explicitly, and the render either covers every tag group or states that the slices do not sum.

One interpretation caveat belongs in the page prose too, because it cuts directly against the item's own thesis. The tier guide blesses *renderer arm tests* as a unit-tier family that is "a different species" from fixture-plumbed generator tests, and the preferred home for per-arm structural assertions. Those land in the `unit` column. A raw unit-versus-pipeline line-coverage comparison will therefore attribute deliberate, doctrine-endorsed unit testing to the arm the doctrine is skeptical of. Whoever reads the numbers has to know that before drawing a conclusion, or the instrument will be used to argue the opposite of what the tier guide says.

## Diagnoses closed at Spec time

Two open questions in the original body are answered, both by reading rather than guessing.

**`graphitron-javapoet` produced no exec data.** Not a wiring problem. Its surefire configuration carries `<skipTests>true</skipTests>`, so its tests never run; the empty report was a correct measurement of zero executed code. The module's `@{argLine}` usage and its local `<argLine/>` property declaration are both correct.

Measured in this session by temporarily removing that one element: **400 tests run, 400 pass, 1 skipped, about 6 seconds** for the whole module. Un-skipping is a one-element change that adds 400 passing tests to the reactor, and it is a change to what the build *runs*, not to what it measures, so it is out of scope here and filed as R560. This item reports the module honestly at whatever its coverage is on the day it lands.

**Why the `@{argLine}` prepend is not sufficient on its own.** Surefire's late property replacement leaves the literal `@{argLine}` on the command line when no `argLine` property is defined, and the JVM then dies on an unrecognised option. `graphitron-javapoet` is the one module in the reactor using `@{argLine}` today, and it is also the one module declaring an empty `<argLine/>` property: that declaration is the workaround, not incidental. So the prepend in the three affected modules requires an `argLine` property to be in scope for them too, which decision 3 of the Implementation handles once at the root rather than three times locally.

## Implementation

**`pom.xml` (root).** Add `<version.org.jacoco>0.8.15</version.org.jacoco>` to `<properties>`, alongside an empty `<argLine/>` property in the same block. The empty property is unconditional and outside the profile: `prepare-agent` overwrites the project property at `initialize` when `-Pcoverage` is on, and every default build gets an empty expansion instead of a literal. Add `jacoco-maven-plugin` to `<pluginManagement>` at that version. Add the `coverage` profile with `prepare-agent` (default phase `initialize`, `<append>false</append>`, nothing else configured) and `report` (default phase `verify`, default formats, which include the CSV the report tool reads).

**`graphitron-javapoet/pom.xml`.** Drop the now-redundant local `<argLine/>` property; the root declaration covers it. Leave `skipTests` alone.

**`graphitron-mcp/pom.xml`, `graphitron-lsp/pom.xml`, `graphitron-maven-plugin/pom.xml`.** Change each hard-set `<argLine>--enable-native-access=ALL-UNNAMED</argLine>` to `<argLine>@{argLine} --enable-native-access=ALL-UNNAMED</argLine>`. The existing comments at all three sites explain the native-access flag; extend each with one clause on why the placeholder leads.

**`roadmap-tool`: `SourceCoverageReport`.** New class next to `LeafCoverageReport`, following its shape closely enough that a reader of one can read the other: in-memory DuckDB, a view over the CSV glob via `read_csv_auto` with `filename=true`, aggregation queries, render, close. No persisted database file. Names are SQL-shaped and singular, and a header comment bridges the Java-to-SQL vocabulary the way `LeafCoverageReport`'s does; rows are keyed on `(module, package, class)`.

Reads two globs from the repo root: `**/target/site/jacoco/jacoco.csv` for the combined figures, and `**/target/site/jacoco-*/jacoco.csv` for the per-tier ones, with the tier name taken from the directory suffix in `filename`. Short-circuits with a clear "no coverage CSV found, run with -Pcoverage" diagnostic when the combined glob matches nothing, the way `leaf-coverage --verify` does for missing traces.

Renders four tables into `roadmap/source-coverage.adoc`:

- one row per module: line, branch and method percentages, the shape of the baseline table above;
- one row per package of `graphitron`, plus the per-tier line-coverage columns when tier data is present, which is the table that answers the pyramid question;
- the 25 classes of `graphitron` with the most missed lines. The cap is stated in the page prose so a reader never mistakes a bounded list for the whole story;
- **the leaf join.** One row per sealed leaf in the hierarchies `LeafCoverageReport` already enumerates, carrying that leaf's trace count beside its implementing class's line coverage. This is the view that makes the dimension worth landing rather than a second disconnected projection, and it is what R117's decomposition rule demands of a new dimension: a query that lights up only once coverage is keyed on class. The two reports' facts are orthogonal and the four quadrants are four different failure modes. Traces but low coverage means the classification is demonstrated while most of its emitter path never runs. No traces and no coverage means dead weight. No traces but high coverage means the leaf's class is exercised by something other than the corpus, which is usually a test asserting the classifier rather than the classification. Traces and high coverage is the healthy case. Only the first two are actionable, and neither report can see them alone.

The join is on class identity: `LeafCoverageReport`'s `leaves.fqn` against JaCoCo's `PACKAGE` plus `CLASS` pair, one normalisation apart. Reuse the leaf enumeration rather than reimplementing it.

**`roadmap-tool`: `CoverageAgentWiringCheck`.** New `check-coverage-agent-wiring` subcommand joining the `check-adoc-tables` / `check-transient-citations` / `check-module-enumeration` family, bound to `verify` in `roadmap-tool/pom.xml` beside them. One walk over every module pom the root `<modules>` declares, enforcing both wiring invariants this item creates, with `BuildFailure` naming the module and the offending element:

- a surefire `<argLine>` present without a leading `@{argLine}` (decision 8);
- a module binding a second test-executing execution (failsafe, or a `forkCount` above one) without its own `prepare-agent` execution writing a distinct `destFile` (decision 2).

Named for the invariant rather than either symptom, so the second rule has an obvious home and a third has somewhere to go.

**`roadmap-tool`: `Main`.** Two subcommand cases and two usage lines. In `runRenderAdoc`, the hardcoded single-report copy of `inference-axis-coverage.adoc` into the staging directory becomes a small list covering both report filenames, with the absent-file branch writing decision 7's stub for the coverage page instead of skipping silently. A glob over `roadmapDir/*.adoc` is deliberately avoided: it would newly publish `workflow.adoc` and the two search how-tos, a site change this item has no business making. Add the sibling link to the new page next to the existing `xref:inference-axis-coverage.adoc[...]` in the adoc status board and next to its markdown counterpart in the README renderer.

**No committed `roadmap/source-coverage.adoc`.** Per decision 7 the absent-file case is handled by the stub `runRenderAdoc` synthesizes, so nothing known-wrong is committed. The generated page's own header prose carries: what the page measures, that CI regenerates it on trunk pushes, the regeneration command, the tier-column caveats from decision 9 (the columns are slices that do not sum, and renderer arm tests land in the `unit` column by design), and both measurement blind spots from decision 6 stated separately, since one is uncollected and the other is collected then discarded at report time.

**`.github/workflows/rewrite-build.yml`.** Add `-Pcoverage` to the `build` job's existing reactor build step, and flip that step's `verify` to `install`. The flip is what makes the tier steps resolvable: `verify` installs nothing, and the tier runs are separate `-pl :graphitron` invocations that resolve `graphitron`'s reactor dependencies (`graphitron-javapoet` compile-scope, `graphitron-sakila-service` test-scope) from the local repository, exactly like the inner-loop command `CLAUDE.md` documents as "assumes a prior full install". Without the flip they fail on the first trunk push while every PR build stays green, since the tier steps are trunk-gated; or worse, they resolve a stale snapshot if the `setup-java` maven cache ever carries one. `install` over `verify` changes nothing else about the build, and PR builds pay only the install phase itself.

Then, after the leaf-coverage pair: the two trunk-gated tier steps (each its tier run and its `report` invocation, both commands exactly as in decision 6 including `-Dleaf-coverage.skip`), then `Regenerate source-coverage report` and `Upload source-coverage artifact`, and a `Download source-coverage artifact` step in `docs-build`, each mirroring its leaf-coverage sibling including `if-no-files-found: error`.

## Contributor documentation (first-client check)

No consumer-facing surface: `-Pcoverage` is a contributor build flag, and `graphitron` is not on a consumer's classpath. The generated page is published publicly, though, so it has to read as a report rather than as build exhaust, and the recipes have to read simply or the design is wrong.

Draft of a new `== Coverage measurement` section for `docs/architecture/how-to/testing.adoc`, to sit after `== Build commands`:

> Coverage is off by default. `-Pcoverage` attaches the JaCoCo agent to every module's test fork and writes a per-module report at `verify`.
>
> ```bash
> # Whole reactor, combined across tiers
> mvn verify -Plocal-db -Pcoverage
>
> # Regenerate the published page from whatever CSVs are on disk
> mvn -pl roadmap-tool exec:java -q -Dexec.args='source-coverage .'
> ```
>
> A module's report attributes only that module's own classes. `graphitron`'s figure is generator-source coverage from its unit and pipeline tiers; `graphitron-sakila-example`'s figure is coverage of *generated* code from the compilation and execution tiers. Two kinds of generator code are missing from both. Code that ran in the Maven process during `graphitron:generate` was never instrumented, because the agent is attached to test forks. Code that ran in another module's test JVM (`GeneratorDeterminismTest` invokes the generator in-process) *was* recorded there, but a module's report analyses only its own classes, so it is dropped when the report is written.
>
> CI publishes the per-tier split for `graphitron` on trunk pushes. To reproduce it locally, run each tier into its own exec file: [the two command pairs from decision 6]. The page grows per-tier columns when it finds those directories. Read the columns as slices, not as a decomposition: `cross-cutting` classes are in neither, so the two do not sum to the combined figure, and the renderer arm tests this guide blesses as a unit-tier family land in the `unit` column by design.

The published page reads as: title, one paragraph on what is and is not measured, the regeneration command, then the four tables. The rendered site links it from the roadmap status board next to the inference-axis coverage report.

## Tests

- `SourceCoverageReportTest` (roadmap-tool): renders from fixture CSVs written to a temp directory tree. Asserts module aggregation arithmetic against hand-computed percentages, AsciiDoc table structure, the stated top-N cap, tier columns present when tier CSVs exist and absent when they do not, the leaf join producing a row per leaf with both facts (including the two actionable quadrants), and the empty-glob diagnostic.
- `CoverageAgentWiringCheckTest` (roadmap-tool): one case per rule and per passing shape. A compliant `<argLine>` passes, a bare one fails with the module named and the false-`0%` consequence in the message, no surefire config at all passes; a module with a second test-executing execution and no distinct `destFile` fails, the same module with one passes. Mirrors `ModuleEnumerationCheckTest`.
- No new tests in `graphitron`. This item adds no generator behaviour, and a test asserting the profile's own wiring would assert Maven's behaviour rather than ours. The wiring is covered by the verification below and, against regression, by `check-coverage-agent-wiring`.
- The hoisted tier vocabulary from decision 9 needs no test of its own; it exists so that `LeafCoverageReportTest` and `SourceCoverageReportTest` cannot disagree about the tier set.

## Verification

1. `mvn install -Plocal-db` with no coverage flag: green, and the three modified modules' test JVMs start (this is the check that the `@{argLine}` prepend did not break the default build, which is the one way this item can break something contributors depend on).
2. `mvn verify -Plocal-db -Pcoverage`: green, and a non-empty `target/jacoco.exec` plus `target/site/jacoco/jacoco.csv` exists in all six test-bearing modules that should produce data: `graphitron` (309 test classes), `graphitron-sakila-example` (69), `graphitron-lsp` (52), `graphitron-mcp` (18), `graphitron-maven-plugin` (14) and `roadmap-tool` (12). The `mcp` / `lsp` / `maven-plugin` trio is deliverable 2's regression, so record their numbers in the landing commit message. `graphitron-javapoet` correctly reports nothing while its 21 test classes are skipped.
3. Modules with no tests do not fail the build: `report` skips with a diagnostic when there is no exec data. This covers `graphitron-fixtures-codegen`, `graphitron-sakila-db`, `graphitron-sakila-service`, `docs`, and `graphitron-jakarta-rest`.

That last one is worth stating plainly rather than letting it emerge as a blank row: `graphitron-jakarta-rest` has zero test classes, and it is the one module in the reactor that is hand-written runtime code on a *consumer's* classpath (hence its Java 17 compile floor). The instrument's first reading will say so. Reporting it is in scope, fixing it is not.
4. `mvn verify -Plocal-db -Pcoverage -T 1C`, matching CI, to confirm module parallelism and the agent coexist. Record the wall-clock delta against the same command without `-Pcoverage`: decision 5 accepts an agent cost on every PR build and this item should not be the one asserting an unmeasured number.
5. The two tier recipes produce distinct exec files and distinct report directories, and `source-coverage` picks up the tier columns. Confirm the slices do not sum to the combined figure, which is decision 9's claim and the thing the page prose promises. Confirm also that `graphitron/target/leaf-coverage.jsonl` is left byte-identical by both tier runs (`-Dleaf-coverage.skip` doing its job), so the inference-axis regen still reads full-suite traces.
6. `check-coverage-agent-wiring` fails on a deliberately reverted `@{argLine}` in one of the three modules, and on a synthetic second test-executing execution. A guard that has never been seen to fail is not a guard.
7. `mvn -pl :graphitron-docs -am package` renders the page from real data, and again with no coverage data on disk to exercise the synthesized stub from decision 7. The status-board link resolves in both.
8. Record the first full reading in the landing commit message so the ad-hoc baseline above and the first instrumented run can be compared.

## Non-goals

* Acting on the coverage numbers, including the emitter gaps in the baseline. This item builds the instrument; follow-ups spend the signal.
* Any gate that *judges the numbers*: thresholds, `jacoco:check`, ratchets. Evidence first; a ratchet is a decision to make once a few windows of data exist, and a premature one would be tuned to a number nobody has interpreted yet. The wiring gate in decision 8 is not this; it judges the instrument, not the reading.
* Instrumenting the Maven JVM to capture the maven-plugin's `generate` executions in downstream modules. Possible later via `MAVEN_OPTS`; the report page states the resulting blind spot rather than hiding it.
* Recovering cross-module generator-source coverage, the second blind spot in decision 6. It is a report-scope choice rather than a collection gap, so a follow-up could spend `report-aggregate` or an explicit class-directory list on it; this item states it and moves on.
* Un-skipping `graphitron-javapoet`'s tests (its own item; see Follow-ups).
* Mutation testing (pitest).
* Coverage of `graphitron-maven-plugin`'s `src/it` invoker builds, which fork their own Maven JVMs.

## Follow-ups to file as Backlog items

- **Un-skip `graphitron-javapoet`'s 400 tests.** Filed while taking this item to Spec as R560: delete one `<skipTests>` element, measured green in about 6 seconds.
- Spend the coverage signal on the weakest emitters, once a few windows of data exist.
- Test `graphitron-jakarta-rest`, the untested consumer-classpath runtime module. To be filed once the instrument has said it out loud, so the item opens with a number rather than an impression.
- Converge `-Pcoverage` and `-Pleaf-coverage` under one measurement umbrella, if R133 lands and the ergonomics still argue for it.

## Relation to existing machinery

Complementary to the leaf-coverage report (`roadmap-tool leaf-coverage`, rendered at `roadmap/inference-axis-coverage.adoc`): that measures which classification-taxonomy leaves the corpus demonstrates, JaCoCo measures which source lines and branches execute. Neither subsumes the other, and the two answer different failure modes: a leaf with no traces is a classification nobody demonstrates, a package at 40% line coverage is code nobody runs.

Structurally this is a second instance of a pattern the repo already has (build emits per-module machine-readable data, roadmap-tool joins it in DuckDB and renders an AsciiDoc page, CI republishes on trunk push), which is why the implementation above is mostly "follow `LeafCoverageReport`" rather than new design.

In R117's programme terms it is another dimension of the same projection, keyed naturally on module, package and class, and the leaf join in the fourth table is what earns that claim. Without it this would be a standalone report borrowing `LeafCoverageReport`'s shape while joining none of its data, which R117's own decomposition rule calls a dimension without a consumer. The join is also why the shared tier vocabulary in decision 9 is worth hoisting rather than duplicating: once the two reports join, a disagreement about the tier set becomes a disagreement inside one page.
