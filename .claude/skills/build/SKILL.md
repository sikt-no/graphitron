---
name: build
description: Choose the cheapest Maven invocation that still covers what changed, using -am, -amd and -rf instead of rebuilding the reactor. Use whenever you are about to run mvn in graphitron-rewrite, and especially before reaching for a bare `mvn install`. A full build is 25-33 minutes; most changes need a fraction of it, and the verification build owed before publishing is a separate question answered in CLAUDE.md.
---

# Build

A full `mvn clean install` is **25 to 33 minutes**. Most of a session's builds do not need to be
one. This skill is about spending the reactor only where it is owed.

## The reactor, in build order, with its costs

Maven orders by dependency, not by the order `<modules>` declares. Module numbers are what `-rf`
counts, so this is the list to read when resuming.

| # | module | cost |
|---|---|---|
| 1 | graphitron-rewrite-parent | 1 s |
| 2 | graphitron-fixtures-codegen | 3 s |
| 3 | graphitron-sakila-db | 27 s |
| 4 | graphitron-sakila-service | 4 s |
| 5 | **graphitron-model** | **5:21** |
| 6 | graphitron-javapoet | 5 s |
| 7 | **graphitron** | **7:24** |
| 8 | graphitron-mcp | 1:44 |
| 9 | graphitron-jakarta-rest | 2 s |
| 10 | graphitron-lsp | 1:47 |
| 11 | graphitron-maven-plugin | 2:27 |
| 12 | **graphitron-sakila-example** | **4:58** |
| 13 | graphitron-roadmap-tool | 37 s |
| 14 | graphitron-docs | 27 s |

Four modules are 23 of the 33 minutes. Everything else is noise.

## The three flags that matter

**`-am` (also-make): the module and everything it needs.** Use when you changed something and want
to know whether it compiles and passes its own tests, and the upstream modules may be stale.

```bash
mvn -o $MVNREPO install -pl graphitron-model -am
```

**`-amd` (also-make-dependents): the module and everything that needs it.** Use when you changed a
module others read and want to know what you broke downstream. This is the one to reach for after
editing `graphitron-model`'s main sources or the DDL.

```bash
mvn -o $MVNREPO install -pl graphitron-model -amd
```

**`-rf` (resume-from): start at a module and continue to the end.** Use when a build got a long way
and then failed for a reason you have fixed, or for a reason that was not the code at all. The
modules before it are already installed from the same tree, so resuming is the same build
continuing rather than a shortcut around it.

```bash
mvn -o $MVNREPO install -rf :graphitron-sakila-example
```

The `:` prefix is the artifactId form and is what `-rf` and `-pl` both accept.

## What those select here, measured

| invocation | modules | reads as |
|---|---|---|
| `-pl graphitron-model -am` | 4 | model and the three it needs |
| `-pl graphitron-model -amd` | 8 | model and the seven that read it |
| `-pl graphitron -am` | 7 | graphitron and its whole upstream |
| `-pl graphitron -amd` | 4 | graphitron and the three above it |

The asymmetry is the reactor's shape. `graphitron-model` is near the bottom, so almost everything
depends on it and `-amd` is the expensive direction; `graphitron` is near the top, so `-am` is.
Knowing which of the two you are asking for is most of the saving.

## Picking the invocation

- **A test you are iterating on**: `-pl <module> -Dtest=ClassName -DfailIfNoSpecifiedTests=false`.
  Seconds, not minutes.
- **One module's own suite**: `-pl <module>`. Add `-am` only if an upstream module changed in this
  session; a bare `-pl` reads the *installed* artifacts and will silently use a stale upstream.
- **Main sources in graphitron-model, or the DDL**: `-pl graphitron-model -amd`. That schema is read
  by every module above it, and the relation-count gates now live in module 5, so a wrong count
  fails in four minutes rather than twenty.
- **Roadmap only**: `mvn verify -pl roadmap-tool,docs`. That is the whole verification a
  roadmap-only diff owes; see CLAUDE.md.
- **Before publishing anything that touches code**: the full reactor. No `-pl`, no `-rf`. See
  CLAUDE.md "Building and testing" for when a green build carries forward over a rebase and when it
  does not.

## Profiles

- `-Pquick` skips tests and the javadoc reference gate. Compilation only.
- `-P!docs` skips the AsciiDoctor render, about 10 s of JRuby startup.
- `-Plocal-db` is required by the full build in environments that have PostgreSQL. **This
  environment has none**; use plain `clean install`. Passing it to `roadmap-tool,docs` earns a
  warning and nothing else, since neither declares it.

## Traps this repo has actually sprung

**A `-Pquick -DskipTests install` leaves the module in a state the codegen driver then fails from.**
The symptom is `generate-model-sources` failing with a class it cannot find. Recover with
`clean install` on that module; do not try to diagnose the missing class.

**Two Maven runs in one worktree collide.** Never start a second while one is running. If you edit
files under a running build, the build may or may not pick them up, and you will not know which.

**Wait on the log, not on a pid.** `pgrep -f` matches the wrong process often enough to matter, and
a wrapper shell exiting is not the build finishing. Wait on the verdict line:

```bash
until grep -qE "^\[INFO\] BUILD (SUCCESS|FAILURE)" build.log; do sleep 30; done
```

**`pkill -f "<pattern>"` kills this shell if the pattern appears in the command line.** It does.
Resolve the pid first, then `kill` it by number.

**Exit 143 is a kill, not a failure.** A tool timeout cutting off a 33-minute build reports the same
way a real failure does. Check for a `BUILD` line before concluding anything.

**Read the failure before rebuilding.** Two of this session's build failures were environmental: a
Quarkus test losing port 8081 to an orphaned process, and an MCP test deserialising short under
concurrent load. Both passed on a rerun with no change. Rerunning is right; recording it as a flake
rather than as a fix is also right.

## Do not sum per-class test times

`graphitron` and `graphitron-model` both run **four test classes concurrently**
(`junit-platform.properties`, `parallel.config.fixed.parallelism=4`). A class's reported
`Time elapsed` is the wall-clock span it was alive, which includes every moment it sat waiting
while other classes held the lanes.

Those spans overlap, so they do not add up to anything. Measured on one build of `graphitron`: the
per-class times sum to **6262 seconds** against a module wall clock of **444 seconds**, which with
four lanes is at most 1776 seconds of capacity. The sum exceeds what physically existed by three and
a half times.

An attempt to find the expensive tests this way produced a target of 1742 seconds for eight classes.
Run on their own the same eight take **80 seconds**. The one that reported 279 takes **42** alone.
Nothing was optimised on the strength of that figure only because it was checked.

The two numbers that mean something:

- **Module wall clock**, from the reactor summary. This is real and comparable.
- **An isolated run**: `-pl <module> -Dtest='<Class>'` and time the invocation. This is the class's
  own cost, and it is the only way to attribute anything.

Before proposing a performance fix, run the suspect alone. The ratio between its reported elapsed
and its isolated cost is contention, and contention is not fixed by making the test cheaper.

## Reporting a build

Give the module count, the test count and the wall clock, from the log rather than from memory:

```bash
grep -cE "^\[INFO\] no.sikt:.*SUCCESS" build.log
awk '/Tests run:.*Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+$/ {
  for (i=1;i<=NF;i++) if ($i=="run:") {gsub(",","",$(i+1)); t+=$(i+1)}
} END {print "tests:", t}' build.log
```
