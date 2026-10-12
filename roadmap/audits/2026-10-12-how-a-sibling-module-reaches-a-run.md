# How a sibling module reaches a run

Measured 2026-10-12 on Maven 3.9.16, with a two-module project, `b` depending on `a`, both at
`1.0-SNAPSHOT`, a private local repository, and `dependency:build-classpath` printing what `b`'s
compile classpath holds for `a`. Filed as an audit so the table survives the item. The project was
throwaway and is not kept; each row takes a minute to reproduce.

## What Maven 3.9 hands a downstream module

| invocation | `b` gets for `a` |
|---|---|
| `mvn compile`, whole reactor | `a/target/classes` |
| `mvn package`, whole reactor | `a/target/a-1.0-SNAPSHOT.jar` |
| `mvn -pl b -am compile` | `a/target/classes` |
| `mvn -pl b compile`, `a` never installed | fails: the dependency does not resolve |
| a goal named without a phase, whole reactor, `a` never installed | fails, `a/target` present or not |
| the same, `a` installed | the installed jar from the local repository, the reactor's newer classes notwithstanding |
| `mvn -pl b compile` or `mvn compile` inside `b`, `a` changed since it was installed | the stale installed jar; `b` fails on a symbol `a` gained |

The rule behind the table is `ReactorReader.find`. A reactor project resolves from the reactor only
when the session built it: its packaged file if the session packaged it, else its output directory
if the session ran `compile` on it, else nothing, and nothing falls through to the local
repository. Being in the reactor is not enough, and a `target/` left by an earlier build is not
looked at.

Maven 4 copies each successful module's artifacts into a project-local repository and resolves a
module the session left out (`-pl` without `-am`) from there before the user's repository. It
narrows the stale installed jar to a stale last build; it does not remove it.

## What the two dev goals do

`graphitron:dev` forks no lifecycle and compiles none of the consumer's code: requires TEST
resolution, and its incremental compiler compiles the generated tree alone, into
`target/graphitron-classes`. A sibling therefore arrives as the table says. Where Maven loaded the
one module the goal runs in, the sibling scan appends each sibling's `target/classes` behind the
resolved classpath, so a stale installed jar comes first in the order the loader, the class headers
and the census follow. The scan names a sibling by its directory, so the installed jar's coordinate
does not match it, and the jar classifies `DECLARED` beside a `SIBLING` directory carrying the same
classes.

`quarkus:dev` forks no lifecycle either, but runs one itself: before starting it executes the
current project's phases from `validate` through `test-compile` (`handleAutoCompile`, skipping goals
the session already ran). Sibling modules come from Quarkus's own workspace discovery
(`BootstrapMavenContext` with workspace discovery on), whose `LocalWorkspace.findArtifact` resolves
a workspace module to its packaged jar in `target/` if one exists, else its `target/classes` if that
exists, and only then defers to the local repository. Existence is the whole test, with no check of
age. The modules found that way are registered as reloadable, their source and output directories
handed to the hot-reload loop.

## What a stale input costs

Recorded 2026-10-05 on opptak, where `graphitron:dev` reported 98 author errors against a correct
schema. Every one was a lookup that came back empty: a service method, a record or exception class,
a table, a foreign key or a column, and every missing name existed in source. Every module's
`target/` dated from 23 July and the installed `opptak-jooq-1.0-SNAPSHOT.jar` from 21 September,
while the sources were current to 5 October: migration `V398__tidlig_opptak_melding.sql` landed on
1 October and `ManglerDatoForAldersberegningException.java` on 28 September. One line before the 98
would have said it, roughly: `opptak-service` was last compiled 2026-07-23 but its sources changed
2026-10-05, so anything added since shows up as not found.

## Sources

- Maven 3.9 `ReactorReader`: https://raw.githubusercontent.com/apache/maven/maven-3.9.x/maven-core/src/main/java/org/apache/maven/ReactorReader.java
- Maven 4 `ReactorReader`: https://raw.githubusercontent.com/apache/maven/master/impl/maven-core/src/main/java/org/apache/maven/ReactorReader.java
- Quarkus `DevMojo`: https://raw.githubusercontent.com/quarkusio/quarkus/main/devtools/maven/src/main/java/io/quarkus/maven/DevMojo.java
- Quarkus `LocalWorkspace`: https://raw.githubusercontent.com/quarkusio/quarkus/main/independent-projects/bootstrap/maven-resolver/src/main/java/io/quarkus/bootstrap/resolver/maven/workspace/LocalWorkspace.java
