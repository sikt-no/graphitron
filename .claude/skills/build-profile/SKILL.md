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

## graphitron-model, measured

`clean test`, unforked, 8:13 total. Ranking holds even though the absolute numbers are inflated.

| mojo | sec | share |
|---|---:|---:|
| `surefire:test` | 285.2 | 58.3% |
| `compiler:compile` | 141.1 | 28.9% |
| `exec:java` (generate-model-sources) | 31.1 | 6.4% |
| `compiler:testCompile` | 20.0 | 4.1% |
| everything else, nine goals | 3.3 | 0.7% |

`compiler:compile` at nearly a third of the module is the standing surprise and nobody has looked
at it. Everything below `testCompile` is noise; do not spend time there.

## The method, and why it matters

**Never attribute cost from reported test times.** `graphitron` and `graphitron-model` run four test
classes concurrently, so a class's `Time elapsed` is the span it was alive including every moment it
waited for a lane. Those spans overlap and do not add up: on one `graphitron` build they total 6262
seconds against a 444-second module, which with four lanes is at most 1776 seconds of capacity.

That method produced a target of 1742 seconds across eight test classes. The same eight, run alone,
take **80 seconds**; the one reporting 279 takes **42**. A plan was written on the wrong figure and
thrown away.

What to do instead, in order:

1. **Module wall clock** from the reactor summary. Real and comparable across runs.
2. **A `settings=default` recording** and `mojos.py`, to find the goal.
3. **An isolated run** of the suspect class, `-pl <module> -Dtest='<Class>'`, timed. This is its own
   cost. The ratio between its reported elapsed and this is contention, and contention is not fixed
   by making the test cheaper.
4. **`settings=profile` with `-DforkCount=0`** only once you know which goal and roughly which
   tests, because the recording is large and reading it is work. The one above is 204 MB.

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
