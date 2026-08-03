---
id: R564
title: "Javadoc reference gate hard-fails on an out-of-range project.build.outputTimestamp (maven-archiver 3.6.4 bundled by maven-javadoc-plugin 3.12.0)"
status: Ready
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

The repo never sets that property, and it does not have to be set for the failure to happen. When the
property is absent, `maven-archiver` falls back to the **`SOURCE_DATE_EPOCH`** environment variable,
the cross-ecosystem Reproducible Builds convention:

```java
if (outputTimestamp == null || (outputTimestamp.length() < 2 && !isNumeric(outputTimestamp))) {
    outputTimestamp = System.getenv("SOURCE_DATE_EPOCH");
    if (outputTimestamp == null) return Optional.empty();
}
```

`SOURCE_DATE_EPOCH=315532800` is `1980-01-01T00:00:00Z` to the second: the ZIP epoch, the value
reproducible-build tooling most often picks as its floor. **NixOS exports exactly that value from
`stdenv`**, so the gate fails for every NixOS contributor out of the box, on any Maven version, with
no Maven configuration involved at all. That is the reported provenance: Maven 3.9.12, property
unset, `SOURCE_DATE_EPOCH=315532800` inherited from the OS. The error message prints the *parsed*
`Instant` rather than the raw input, which is why an ISO string appears in it even though the input
was a numeric epoch.

This makes the item a portability bug rather than a local misconfiguration: the environment is doing
the conventional, correct thing, and the gate is the only execution in the reactor that cannot cope.
It dies before doing any of its own work, so the whole build is lost to it.

## Mechanism

`maven-javadoc-plugin` binds `outputTimestamp` to `${project.build.outputTimestamp}` and, in the
plain `javadoc` goal, feeds it to `MavenArchiver.parseBuildOutputTimestamp` for two cosmetic
purposes only: substituting `{currentYear}` into `bottom`, and forcing `-notimestamp` on. The gate
sets `notimestamp` itself and discards the rendered output, so neither purpose matters here.

The plugin pins `maven-archiver` **3.6.4**, whose `parseBuildOutputTimestamp` range-checks *both* the
ISO and the numeric-epoch branch against a `1980-01-01T00:00:02Z` floor and throws. The floor sits two
seconds above the ZIP epoch, so the canonical `SOURCE_DATE_EPOCH` value fails the check that exists to
keep timestamps ZIP-representable. **3.6.5** dropped the range check entirely: it now throws only on a
value it cannot parse at all. So the failure is a stale shared component reached through a code path
that does not need the parsed value.

## Reproduction and verified fix

Both paths reproduce on the current tree under Maven 3.9.11 / JDK 25, from a clean environment:

```bash
# the reported shape: environment variable only, property unset
SOURCE_DATE_EPOCH=315532800 mvn -pl graphitron-javapoet javadoc:javadoc@check-link-references

# equivalently, via the property
mvn -pl graphitron-javapoet javadoc:javadoc@check-link-references \
    -Dproject.build.outputTimestamp=1980-01-01T00:00:00Z
```

Overriding the plugin's bundled `maven-archiver` to 3.6.5 in the root pom's `pluginManagement`
entry turns both commands green (verified against the `SOURCE_DATE_EPOCH` form, which 3.6.5 still
honours, minus the range check):

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

This is preferable to declaring `project.build.outputTimestamp` in the root pom. That would also work,
by short-circuiting the `SOURCE_DATE_EPOCH` fallback, but it fixes the symptom by overriding what the
environment asked for, and it quietly commits the project to a Reproducible Builds policy (with a
value to bump at every release) as a side effect of a bug fix. Whether graphitron *wants* reproducible
published artifacts is a separate question worth its own item.

The override is self-retiring: drop it once `maven-javadoc-plugin` ships a release that pins
`maven-archiver` >= 3.6.5. Placing it in `pluginManagement` covers the `attach-javadocs` `jar` goal
in the `release` profile too, which reaches the same parse.

## Implementation

Two files, no generator or runtime code.

**`pom.xml`**, the `pluginManagement` entry for `maven-javadoc-plugin`: add the `maven-archiver`
3.6.5 `<dependencies>` block shown above, with an XML comment stating what it works around and the
condition for deleting it. The comment belongs here rather than in `CLAUDE.md` because the reader who
needs it is whoever bumps the `<version>` on the line above, and a plugin bump that lands a
`maven-archiver` >= 3.6.5 is exactly when the override should go.

**`.github/workflows/rewrite-build.yml`**, the `Build rewrite reactor` step: add
`env: SOURCE_DATE_EPOCH: 315532800`. See the next section for why this is the guard and why it is
safe.

The javadoc gate is the sole failure point, so nothing else needs touching. Everything through
`package` is clean without the override (`mvn package -Plocal-db -DskipTests` succeeds across the
reactor under the bad value), which localises the defect to the one plugin that pins the stale
`maven-archiver`.

## Regression guard

A version override on a plugin's transitive dependency has no natural test. Asserting the
`pluginManagement` block exists from a meta-test would pin XML shape rather than behaviour and would
pass just as happily if the plugin bump that made the override unnecessary also made it wrong.

The behavioural guard is to make CI reproduce the reporter's environment: export
`SOURCE_DATE_EPOCH=315532800` on the existing reactor build step. Every CI run then exercises the
NixOS condition at no extra wall-clock cost, and dropping the override turns the build red on the
commit that drops it rather than on the next NixOS contributor's first checkout.

The non-obvious hazard is that `SOURCE_DATE_EPOCH` is not inert once honoured: `maven-archiver` stamps
archive entries with it, so a 1980 mtime could in principle reach a staleness comparison, the
compiler plugin's source-versus-class check or an unpacked jOOQ catalog jar being the plausible
victims. Measured rather than assumed: a full `mvn install -Plocal-db` with the override applied and
`SOURCE_DATE_EPOCH=315532800` exported passes all four tiers, execution tier against PostgreSQL
included, 170 roadmap-tool tests and the rest of the reactor green. The guard is safe to add.

Reviewer's call worth making explicitly: this puts a Reproducible-Builds-adjacent setting on the CI
build path. It is scoped to the test-and-verify job and touches nothing the publish workflow does, so
it does not decide the policy question below. If that still reads as too much coupling for a bug fix,
the fallback is to drop the CI env var and accept the override as untested, which leaves the failure
mode discoverable only by contributors who hit it.

## Verification

- `SOURCE_DATE_EPOCH=315532800 mvn -pl graphitron-javapoet javadoc:javadoc@check-link-references`
  fails before the change, passes after. Both directions already confirmed on this tree.
- `SOURCE_DATE_EPOCH=315532800 mvn install -Plocal-db` green. Already confirmed.
- Unset-environment builds unaffected: the override only changes which `maven-archiver` the javadoc
  plugin loads, and with no property and no env var `parseBuildOutputTimestamp` returns empty on both
  versions.

## Not in scope

Declaring `project.build.outputTimestamp` in the root pom, and with it a Reproducible Builds policy
for published artifacts. That is a real question, with real value for a library on Maven Central, and
it wants its own item: a fixed timestamp has to be bumped at each release, and deciding that as a
side effect of unbreaking a build gets the reasoning backwards.

No user-facing documentation surface: this is contributor-facing build tooling, so the
first-client docs-draft rule does not apply. The javadoc gate's description in
`docs/architecture/explanation/development-principles.adoc` describes what the gate enforces, which
this change does not alter.
