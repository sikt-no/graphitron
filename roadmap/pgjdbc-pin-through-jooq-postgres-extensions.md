---
id: R1187
title: "jooq-postgres-extensions pulls pgjdbc 42.7.7 past the root pin into six reactor modules"
status: Backlog
bucket: cleanup
priority: 4
theme: tooling
depends-on: []
created: 2026-11-16
last-updated: 2026-11-16
---

# jooq-postgres-extensions pulls pgjdbc 42.7.7 past the root pin into six reactor modules

## Goal

Every module in the reactor resolves the PostgreSQL JDBC driver (pgjdbc, `org.postgresql:postgresql`)
at the root `version.postgresql` pin, whichever path brings it in. After that, a dependency scanner
(a tool such as Dependency-Track that matches every resolved library version against published
security advisories) stops reporting GHSA-98qh-xjc8-98pq and GHSA-j92g-9f8w-j867 against the
repository, and the next transitive edge that reaches for the driver lands on the pin rather than on
whatever its own pom declares. Nothing a consumer installs changes: the modules that carry the old
driver at compile scope are not published, and everywhere else it is test scope.

## What is open, and where it comes from

A Dependency-Track scan of trunk taken 2026-11-16 lists two high-severity pgjdbc advisories, both
against `org.postgresql:postgresql` 42.7.7. The same scan lists 42.7.13, the root pin, with no
advisory against it. R970 recorded GHSA-98qh as fixed in 42.7.11; the scan's text for GHSA-j92g
names 42.7.12.

42.7.7 is not ours. `jooq-postgres-extensions` 3.20.11 declares the driver at compile scope as
`${postgres.version}`, and `jooq-parent` 3.20.11 sets that property to 42.7.7. The root pom states
the driver version only as a property, `version.postgresql`, which reaches the three places that
name it directly (the jOOQ codegen plugin's dependencies in `graphitron-sakila-db`, and test-scope
declarations in `graphitron` and `graphitron-sakila-example`). A property is substituted where it
is referenced and nowhere else; what governs a transitive edge is a `<dependencyManagement>` entry,
and the root pom has none for the driver. So the extension's own declaration wins.

The extension entered `graphitron-sakila-db` on 2026-11-13 (`d556e8e56`, so a `daterange` column
generates as `LocalDateRange` the way a consumer's catalog does). That is after R970 shipped the
42.7.13 pin, which is why R970's reactor check did not see this path: it did not exist yet.

`mvn dependency:tree -Dincludes=org.postgresql:postgresql` on trunk at filing:

[cols="2,1,1,3"]
|===
| Module | Driver | Scope | Path

| `graphitron-sakila-db` | 42.7.7 | compile | `jooq-postgres-extensions`
| `graphitron-sakila-service` | 42.7.7 | compile | `graphitron-sakila-db`, then as above
| `graphitron-model` | 42.7.7 | test | `graphitron-sakila-db` (test), then as above
| `graphitron-mcp` | 42.7.7 | test | as `graphitron-model`
| `graphitron-lsp` | 42.7.7 | test | as `graphitron-model`
| `graphitron-maven-plugin` | 42.7.7 | test | as `graphitron-model`
| `graphitron` | 42.7.13 | test | direct declaration, which nearest-wins prefers
| `graphitron-sakila-example` | 42.7.13 | test | direct declaration
|===

The two compile-scope modules set `maven.deploy.skip`, and test scope is not transitive, so no
published pom carries the edge. Neither advisory's preconditions hold here either: GHSA-j92g needs
`channelBinding=require`, and GHSA-98qh needs a malicious server answering SCRAM authentication,
while every connection the build makes is to a database it or the developer started (a
Testcontainers instance, or the local one `-Plocal-db` names). The cost
is the scanner report, and whoever reads it re-deriving the same verdict.

## Likely shape

One entry in the root `<dependencyManagement>`, beside the `jooq-postgres-extensions` entry that
brought the edge in, with a comment saying why it exists:

```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <version>${version.postgresql}</version>
</dependency>
```

Management applies to every edge in a module's graph, so this lifts the transitive declaration in
all six modules at once, and any later puller with it.

One interaction for Spec to settle. `graphitron-sakila-example` imports the Quarkus platform BOM
(the bill of materials Quarkus publishes to pin one tested set of versions), and that BOM manages
the driver too, at the same 42.7.13 today but with an exclusion of
`org.checkerframework:checker-qual`, which the driver's own pom declares at runtime scope. An
inherited explicit entry takes precedence over an imported one, so a bare root entry would drop
that exclusion and put `checker-qual` back on the example's test classpath. Two choices: carry the
same exclusion on the root entry, which keeps the example's resolved tree identical and removes the
jar from the other six modules too; or leave the entry bare and accept the jar in the example. The
first is the recommendation, since the example then resolves exactly what Quarkus tests against.

The direct `${version.postgresql}` declarations in `graphitron` and `graphitron-sakila-example` become
redundant under the managed entry but stay harmless; the codegen plugin's dependency block in
`graphitron-sakila-db` keeps its explicit version regardless, because `<dependencyManagement>` does
not govern plugin dependencies. Whether to drop the two redundant versions is a tidiness call for
Spec, not part of the goal.

## Tests

Acceptance is resolved-tree output recorded in the implementation commit, plus the existing
suites passing in the verification build. The check must be one that passes only when empty, so it
cannot be read selectively:

- **Reactor.** `mvn dependency:tree -Dincludes=org.postgresql:postgresql` over the whole reactor
  prints no `org.postgresql:postgresql` line at any version other than the pin.
- **Example unchanged.** The full `dependency:tree -pl graphitron-sakila-example` is identical
  before and after (with the recommended exclusion), so the Quarkus-managed classpath did not move.
- **Runtime.** The `graphitron-sakila-db` codegen and the execution tiers that drive the driver pass
  in the verification build.

No new test is added: the property is a resolved version, which the tree observes directly.

## Other solutions we've considered

- **Exclude the driver from `jooq-postgres-extensions` in `graphitron-sakila-db`.** Removes the
  edge rather than managing it. The extension's `hstore` binding calls the driver's
  `org.postgresql.util.HStoreConverter` (the one driver class its 3.20.11 jar references), so the
  exclusion holds only while no fixture column is an `hstore`, or while something else supplies the
  driver; and it leaves the next puller unmanaged.
- **Declare the driver directly in `graphitron-sakila-db`.** Nearest-wins would fix that module
  and its dependents through it, but not a future edge that arrives another way.
- **An enforcer rule banning any driver version but the pin.** Once the edge is managed, a
  transitive declaration cannot diverge; only a module's own explicit version could, which review
  catches. A standing rule earns its keep with a scanner in CI, which R970 already set aside as its
  own decision.
