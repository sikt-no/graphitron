---
id: R564
title: "Javadoc reference gate hard-fails on an out-of-range project.build.outputTimestamp (maven-archiver 3.6.4 bundled by maven-javadoc-plugin 3.12.0)"
status: Backlog
bucket: bug
priority: 4
theme: tooling
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# Javadoc reference gate hard-fails on an out-of-range project.build.outputTimestamp (maven-archiver 3.6.4 bundled by maven-javadoc-plugin 3.12.0)

The `check-link-references` javadoc execution in the root pom aborts the build outright, on every
module, when `project.build.outputTimestamp` resolves to `1980-01-01T00:00:00Z`:

```
Failed to execute goal org.apache.maven.plugins:maven-javadoc-plugin:3.12.0:javadoc
  (check-link-references) on project graphitron-javapoet: ... failed:
  '1980-01-01T00:00:00Z' is not within the valid range
  1980-01-01T00:00:02Z to 2099-12-31T23:59:59Z
```

The repo never sets that property, so the value arrives from the developer's Maven environment: a
Maven 4 build (Reproducible Builds mode is active by default there and pins a fixed
`project.build.outputTimestamp`), a `~/.m2/settings.xml` profile, a `MAVEN_ARGS` export, or a
`.mvn/maven.config` in a directory above the checkout. Whatever the source, the gate is the only
execution in the build that dies on it, and it dies before doing any of its work.

## Mechanism

`maven-javadoc-plugin` binds `outputTimestamp` to `${project.build.outputTimestamp}` and, in the
plain `javadoc` goal, feeds it to `MavenArchiver.parseBuildOutputTimestamp` for two cosmetic
purposes only: substituting `{currentYear}` into `bottom`, and forcing `-notimestamp` on. The gate
sets `notimestamp` itself and discards the rendered output, so neither purpose matters here.

The plugin pins `maven-archiver` **3.6.4**, whose `parseBuildOutputTimestamp` range-checks against a
`1980-01-01T00:00:02Z` floor and throws. **3.6.5** dropped that check: it now throws only on a value
it cannot parse at all. So the failure is a stale shared component reached through a code path that
does not need the parsed value.

## Reproduction and verified fix

Reproduces on the current tree under Maven 3.9.11 / JDK 25:

```bash
mvn -pl graphitron-javapoet javadoc:javadoc@check-link-references \
    -Dproject.build.outputTimestamp=1980-01-01T00:00:00Z
```

Overriding the plugin's bundled `maven-archiver` to 3.6.5 in the root pom's `pluginManagement`
entry turns that same command green:

```xml
<artifactId>maven-javadoc-plugin</artifactId>
<version>3.12.0</version>
<dependencies>
    <dependency>
        <groupId>org.apache.maven</groupId>
        <artifactId>maven-archiver</artifactId>
        <version>3.6.5</version>
    </dependency>
</dependencies>
```

This is preferable to declaring `project.build.outputTimestamp` in the root pom, which would fix the
symptom by overriding whatever the developer's environment asked for, and would quietly commit the
project to a Reproducible Builds policy (with a value to bump at every release) as a side effect of a
bug fix. Whether graphitron *wants* reproducible published artifacts is a separate question worth its
own item.

The override is self-retiring: drop it once `maven-javadoc-plugin` ships a release that pins
`maven-archiver` >= 3.6.5. Placing it in `pluginManagement` covers the `attach-javadocs` `jar` goal
in the `release` profile too, which reaches the same parse.

## Scope

- The `maven-archiver` override plus a comment recording why it exists and when it can go.
- Verify with the reproduction command above, and with a full `mvn install -Plocal-db` carrying an
  out-of-range `-Dproject.build.outputTimestamp`. Everything through `package` is already confirmed
  clean under that override (`mvn package -Plocal-db -DskipTests` succeeds across the reactor), so
  the plugins that also parse the property, `maven-jar-plugin` foremost, resolve a `maven-archiver`
  new enough to tolerate the value. The verify-phase javadoc gate is the sole failure point.
- No generator or runtime code is touched.
