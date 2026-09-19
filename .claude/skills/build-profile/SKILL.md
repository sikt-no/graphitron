---
name: build-profile
description: Measure where build and test time actually goes, using the JFR Maven extension this repo registers. Use before proposing any build or test performance work, and whenever a number you are about to act on came from summing reported test times. The setup is fiddly and has been rediscovered once already; the wrong method here produced an estimate that was out by a factor of twenty-two.
---

# Build profile

One extension is registered in `.mvn/extensions.xml`:

```xml
<extension>
  <groupId>com.github.marschall</groupId>
  <artifactId>jfr-maven-extension</artifactId>
  <version>0.2.0</version>
</extension>
```

It emits `JfrEventListener$MojoEvent` and `JfrEventListener$ProjectEvent` into whatever Flight
Recording is running. `MojoEvent` carries `duration`, `goal`, `phase`, `groupId`, `artifactId`,
`version`, `executionId`, `forked`, plus `startTime` and `eventThread`.

That last pair is why this is the only profiler here. A per-goal duration table can be had from
several tools, but `startTime` and `eventThread` are what answer *did these overlap*, and overlap is
the question that has produced every bad build measurement in this repo so far. One recording also
puts the mojo boundaries and the allocation, GC and execution samples inside them in the same file,
so "surefire is 58% of the module" and "here is the hot method" stop being two tools to correlate
by hand.

`fr.jcgay.maven:maven-profiler` was registered here for a while and has been removed. It answered
strictly less than the above and was a second thing to keep working.

## Recording a build

The extension records events but does **not** start a recording. JFR is started separately:

```bash
export MAVEN_OPTS="-XX:StartFlightRecording=filename=/abs/path/build.jfr,settings=default,dumponexit=true \
  -XX:FlightRecorderOptions=stackdepth=256 -Xmx4g"
mvn clean test -pl <module> -DforkCount=0
```

Four parts of that are load-bearing.

**`settings=default`** for timings. It costs about a percent. Switch to `settings=profile` only when
you need method-level stacks, and then stop quoting the durations: the run below came in at 8:13
against the module's usual 5:21, so profile settings and unforked tests together inflated it by half.

**`-DforkCount=0`** to see inside surefire. Surefire forks a JVM by default, so a recording taken in
the Maven JVM sees one opaque `MojoEvent` for the whole test run. Unforked, the tests execute in the
JVM being recorded and their allocation, GC, locks and stacks all land in the file.

**`-Xmx4g`** because unforked tests share the Maven heap, which is otherwise sized for Maven.

**`dumponexit=true`** because the file is written at exit. A recording that looks empty mid-run is
not broken.

## Reading it

```bash
jfr summary build.jfr                                   # event counts; check the Maven events exist
python3 .claude/skills/build-profile/mojos.py build.jfr # per-goal table, ranked, with percentages
jfr print --events jdk.ExecutionSample build.jfr | head -100
```

Check the `JfrEventListener` events appear in `jfr summary` before trusting a run. If the extension
did not load you still get a valid JVM recording with no Maven structure in it, which looks exactly
like success.

**Parse durations from `--json`, never from `jfr print`'s text.** The text format renders long
durations as `4 m 45 s`. Anything that strips non-digits reads that as 445, so a 285-second goal
reports as 445 seconds and the error grows with the thing you are hunting. `mojos.py` uses
`--json`, which emits ISO-8601 (`PT4M45.2S`). This mistake was made here and caught only because a
second tool disagreed.

Sanity check any table against the reactor's own total. The sum of mojos should land just under
module wall clock; if it exceeds it, the parse is wrong or goals genuinely ran concurrently, and
`eventThread` tells you which.

## The reactor, measured

One forked `clean install` with `settings=default`, 22:26 wall. Mojo time sums to 1353 s against
1346 s of wall clock, so the reactor is sequential and a second removed from a goal is a second off
the build.

| goal, all modules | sec | share |
|---|---:|---:|
| `surefire:test` (12 modules) | 696.5 | 51.5% |
| `javadoc:javadoc-no-fork` | 189.7 | 14.0% |
| `graphitron:generate` | 113.5 | 8.4% |
| `compiler:compile` | 97.5 | 7.2% |
| `invoker:run` | 52.5 | 3.9% |
| `jooq-codegen-maven:generate` | 41.2 | 3.0% |

Largest single goals: `graphitron` tests 262.9 s, `sakila-example` tests 148.3 s, `graphitron-model`
javadoc 144.0 s, `sakila-example` `graphitron:generate` 113.5 s, `graphitron-model` tests 99.1 s.

`MojoEvent.artifactId` is the *plugin's*, not the module's. Attribute a goal to a module by nesting
its `startTime` inside the `ProjectEvent` windows, and check those windows are disjoint before
trusting the result. They are here, bar an 8.4 s `capture-only` nested build.

## The javadoc reference gate is 14% of a clean build

`javadoc-no-fork` with `doclint=reference` runs at `verify` in every module. It is not waste: the
pom documents it as the only check of `{@link}` and `{@see}` reference validity, and its sourcepath
includes `target/generated-sources/jooq`, so it reads the generated jOOQ sources too.

Measured on the worst module, `clean verify -pl graphitron-model -DskipTests`:

| | wall |
|---|---:|
| with the gate | 168 s |
| `-Dmaven.javadoc.skip=true` | 47 s |
| **gate** | **121 s** |

**It only costs anything on a clean build.** The plugin is stale-checked, so on a warm `target/` it
is a no-op; the first attempt to measure this ran without `clean` and showed 48 s against 47 s,
which reads as "the gate is free" and is wrong. It is a tax on the verification build, not on
iteration.

So `-Dmaven.javadoc.skip=true` buys about 190 s on a full clean build and is right for iteration,
but the verification build owed before publishing must keep the gate. `-Pquick` is the wrong lever:
it drops the tests as well.

## A long span is not an exclusive cost

The overlap error has a third form, and it survives knowing about the other two. Under a
profiled unforked run `DerivedReadCostTest` held a span of 259 s inside a 280 s test phase and
closed the phase, which reads exactly like a critical path. It is not. Deleting it from the run
saved **13 seconds of 135**.

Two things were wrong. The span was measured on a `settings=profile` run, where the phase was
280 s against 135 s unprofiled, and the inflation was not spread evenly: that class drives many
small H2 scans under a millisecond `ReadBudget`, so per-sample overhead compounds there far worse
than elsewhere. And a span that ends when the phase ends means only that it was still running at
the end, which under a saturated pool is true of whatever finishes last. Its 79 s of reported
elapsed time unprofiled overlaps some 66 s with other lanes.

**The only way to price a test class is to remove it and re-run.** Two runs, no `clean` so
codegen and compile do not vary, and compare wall clock:

```bash
mvn test -pl <module>
mvn test -pl <module> -Dtest='!<Class>' -DfailIfNoSpecifiedTests=false
```

Profile to find *where* time goes inside a layer. Never to decide what to delete.

## Traps

**`forkCount=0` changes isolation and the working directory.** Forked surefire runs with the
working directory set to the module basedir; unforked it inherits Maven's. In `graphitron-model`
this fails `LintReadsRowsTest`, which resolves the relative path
`src/main/java/no/sikt/graphitron/model/lint`. A test depending on a fresh JVM can fail the same
way. Both are properties of the measurement run, not defects to go fixing, and a profiled run that
ends in `BUILD FAILURE` for only these is still readable.

**A failed build's numbers are not the build's numbers.** `generate-model-sources` was recorded here
as 1.813 s and written up as too small to bother with. That came from a run that failed after 3.662
s with the codegen barely started; the real figure is 31.1 s. Check for `BUILD SUCCESS` before
reading a profile.

**Killing a build mid-compile breaks the next one.** `graphitron-model` then fails at
`generate-model-sources` with a class it cannot find. Recover with `clean`; this has caught two
people, one of them the author of this file, an hour after writing the warning into the build skill.
