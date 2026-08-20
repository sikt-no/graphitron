---
id: R764
title: "graphitron-model ships its junit-platform.properties to three consumers that never asked for it"
status: Backlog
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# graphitron-model ships its junit-platform.properties to three consumers that never asked for it

`graphitron-model/src/test/resources/junit-platform.properties` turns on four-thread class-level test
parallelism for that module. It also rides along in the module's test-jar, and three modules consume
that test-jar at test scope: `graphitron`, `graphitron-lsp` and `graphitron-mcp`. So the file now
configures four modules' test runs, one of them deliberately. In `graphitron-lsp` and `graphitron-mcp`
it silently enabled parallelism those modules never declared, worth **17.5 s and 7.5 s** respectively,
which is a real win arriving through an invisible channel. In `graphitron` it put a second
`junit-platform.properties` on the classpath and produced the launcher warning that `graphitron`'s own
pom carries an explicit exclusion to prevent. Whichever way the parallelism question is settled, the
provenance has to become visible: a contributor reading `graphitron-lsp`'s pom and test resources
today cannot discover why its test classes run concurrently, and a test there that is not
thread-safe will start flaking with no local cause to find.

## The mechanism, and that it was already known

`graphitron/pom.xml` configures `maven-jar-plugin`'s `test-jar` goal with an explicit exclusion, and
the comment beside it states the hazard exactly:

> junit-platform.properties configures this module's own test runs (autodetection of
> ClassificationTraceContextExtension). It must not ride along in the test-jar: consumers
> (graphitron-sakila-example) that only want the tier-annotation vocabulary would otherwise get a
> second junit-platform.properties on their classpath, producing the "Discovered 2
> 'junit-platform.properties'" launcher warning on every surefire run.

`graphitron-model/pom.xml` has no such exclusion, because when its `test-jar` was set up the module
had no properties file. The file arrived later, and nothing connected the two facts. The predicted
warning is now in the tree, in `graphitron/target/surefire-reports/null-output.txt`:

```
Discovered 2 'junit-platform.properties' configuration files on the classpath (see below);
only the first (*) will be used.
```

`graphitron` is unharmed in behaviour: its own file is in `target/test-classes`, which precedes
dependency jars on the classpath, so its own configuration wins and the second copy is only noise.
`graphitron-lsp` and `graphitron-mcp` have no file of their own, so for them the second copy is the
only copy and it takes effect.

## What the leak is worth, measured

`mvn test -pl :<module> -Plocal-db`, arms alternated, the properties entry removed from and restored
to the installed test-jar between runs so that nothing else differs.

| Module | With the entry | Without | Delta |
|---|---|---|---|
| `graphitron-lsp` | 33.4 s, 33.0 s | 50.1 s, 50.8 s | **-17.5 s, -35%** |
| `graphitron-mcp` | 26.9 s, 28.1 s | 35.0 s | **-7.5 s, -23%** |

Repeatable to under half a second per arm. User CPU time is about 115 s for `graphitron-lsp` in *both*
arms, which is worth noting because it means these tests were always multi-threaded internally, from
the language server, the tree-sitter natives and the async warm-up; what the file adds is overlap
*between* classes, not threads where there were none.

So this is 25 seconds of a 339-second build, arriving by accident, and the accident is the only reason
anyone would find it. A separate measurement of the same change reported "lsp and mcp: nothing",
taken with `-pl` against an installed `graphitron-model` test-jar that predated the change. That is
the footgun `CLAUDE.md` warns about under the scoped-build command, and it is worth recording here as
the reason the number was missed rather than as an aside: a `-pl` A/B is only valid when the arm being
varied is what got installed.

## What a Spec pass has to settle

* **Whether `graphitron-lsp` and `graphitron-mcp` should run their test classes concurrently at all.**
  They now do. The 25 s says yes. If the answer is yes, each module should say so in its own
  `src/test/resources/junit-platform.properties`, where a contributor will find it, and the file
  should stop arriving from `graphitron-model`.
* **Whether the exclusion belongs on `graphitron-model`'s `test-jar`, or somewhere that cannot be
  forgotten again.** Two modules have now needed the same exclusion, one of them found by accident.
  Options are a third copy of the exclusion, pluginManagement in the parent pom so every `test-jar`
  execution in the reactor excludes it by default, or a test that fails when a reactor test-jar
  contains the file. The last is the only one that cannot silently rot, and it is cheap: the reactor
  already carries meta-tests of this kind.
* **Whether the "Discovered 2" warning should be an error.** It is currently in a redirected output
  file that nothing reads, which is how this survived a full green build. Whether the launcher warning
  can be made to fail a build, and whether that is proportionate, is worth one paragraph.
* **What happens to `graphitron`'s own numbers if it ever loses the race.** Classpath ordering is what
  keeps `graphitron`'s own file winning today. Nothing pins that ordering, and if it inverted,
  `graphitron` would silently lose `junit.jupiter.extensions.autodetection.enabled=true` and with it
  the auto-registration of `ClassificationTraceContextExtension`, which the leaf-coverage trace
  depends on. Decide whether that is a hazard worth a guard or a theoretical one worth a sentence.

## How to re-measure

```bash
# Is the file in the test-jar?
unzip -l ~/.m2/repository/no/sikt/graphitron-model/10-SNAPSHOT/*-tests.jar | grep junit-platform

# What the leak is worth, one module at a time. Keep a copy of the jar, strip the entry,
# alternate the arms; a single pair is not enough for a delta this size.
zip -d <the jar> junit-platform.properties
mvn test -pl :graphitron-lsp -Plocal-db

# Is the warning firing?
grep -r "Discovered 2 'junit-platform.properties'" graphitron/target/surefire-reports/
```
