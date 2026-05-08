---
id: R111
title: "Make graphitron-maven-plugin IT self-contained via extraArtifacts"
status: Spec
bucket: cleanup
depends-on: []
---

# Plan: extraArtifacts seam for graphitron-maven-plugin IT

> The two ITs under `graphitron-maven-plugin/src/it/` fail in `Rewrite reactor CI` because the forked child Maven cannot resolve `no.sikt:graphitron-sakila-db:10-SNAPSHOT`. CI runs `verify`, which never installs sibling reactor modules into `~/.m2`, and the invoker only seeds the IT local-repo with the project under test plus its own dep tree. Add the missing artifact via `<extraArtifacts>` so the seeding stays scoped to the IT execution, no top-level lifecycle change required.

## Why

The Rewrite reactor CI workflow runs `mvn ... verify -Plocal-db`, which never installs sibling reactor modules into `~/.m2`. When `maven-invoker-plugin:run` forks a separate Maven invocation for each IT under `graphitron-maven-plugin/src/it/` (`basic-generate`, `missing-schema-inputs`), that child build cannot resolve `no.sikt:graphitron-sakila-db:10-SNAPSHOT`, which the IT poms declare as a plugin dependency. The result is two failed IT builds and a red CI check. Local builds mask the problem because the documented developer workflow uses `mvn install -Plocal-db`, which populates `~/.m2`.

The `maven-invoker-plugin:install` goal already seeds the IT local-repo with `graphitron-maven-plugin` and its **declared dependency tree** (which is why `graphitron`, `graphitron-lsp`, `graphitron-javapoet` already resolve correctly today). Reactor-cache resolution lets the install mojo copy sibling `target/` jars into the IT local-repo even when those siblings have not yet been installed to `~/.m2`. The gap is artifacts that **only the IT pom** depends on, like `graphitron-sakila-db`: the install mojo has no way to discover them from the project-under-test's dependency tree.

`<extraArtifacts>` on the same `maven-invoker-plugin` configuration is the documented seam for this case. It rides the same reactor-cache resolution, so it works under CI's `verify` lifecycle without requiring any top-level changes.

## Change

### 1. `graphitron-rewrite/graphitron-maven-plugin/pom.xml`

Extend the existing `maven-invoker-plugin` configuration block with an `<extraArtifacts>` list:

```xml
<configuration>
    <cloneProjectsTo>${project.build.directory}/it</cloneProjectsTo>
    <settingsFile>src/it/settings.xml</settingsFile>
    <postBuildHookScript>verify.groovy</postBuildHookScript>
    <showErrors>true</showErrors>
    <extraArtifacts>
        <extraArtifact>no.sikt:graphitron-sakila-db:${project.version}</extraArtifact>
    </extraArtifacts>
</configuration>
```

Only one entry is needed. `graphitron-sakila-db`'s sole runtime dependency is `org.jooq:jooq` (external, resolves from Maven Central); its `graphitron-fixtures-codegen` reference is a build-time plugin dependency used during jOOQ codegen and is not part of the runtime artifact, so the IT does not need it.

### 2. `graphitron-rewrite/graphitron-maven-plugin/src/it/settings.xml`

Replace the comment that asserts external-prior-install as the contract:

> Settings for maven-invoker-plugin integration tests. invoker:install puts the just-built plugin into the local repository before the IT child sessions run. All other artifacts must already be present in the local repository from a prior build.

with wording that names `<extraArtifacts>` as the seam, so a future reader extending the plugin with a new IT knows where to add a sibling-module reference rather than re-introducing the implicit `mvn install` dependency.

## Verification

- **Local repro of the broken state.** Wipe the cached snapshot and run the same lifecycle CI runs:
  ```bash
  rm -rf ~/.m2/repository/no/sikt/graphitron-sakila-db
  mvn -f graphitron-rewrite/pom.xml verify -Plocal-db --batch-mode
  ```
  Without the fix, both ITs fail with `Could not find artifact no.sikt:graphitron-sakila-db:jar:10-SNAPSHOT`. With the fix, both pass.
- **CI on the implementation branch.** The `Rewrite reactor CI` workflow run is the canonical confirmation, since CI is the path that surfaced the bug.
- **No new IT.** Adding a third IT pom to lock the seam down would mean inventing a synthetic sibling-module dependency. The settings.xml comment serves the same documentation role with no ongoing maintenance cost; if a real future IT pulls in a different sibling, that becomes the regression test naturally.

## Out of scope

- **Switching CI from `verify` to `install`.** Would mask the issue rather than fix it. Would also write snapshot artifacts into the runner's `~/.m2`, which is shared with the cache step and has no business holding rewrite-internal `10-SNAPSHOT` jars.
- **Adding a profile or CI flag toggle.** The `<extraArtifacts>` entry is unconditional and harmless: it always seeds the IT local-repo regardless of how the parent build was invoked. Flag-gating would add complexity without payoff.
- **Auditing other reactor modules for similar latent IT-resolution issues.** `graphitron-maven-plugin` is the only IT-housing module in the rewrite reactor today. Revisit if a second appears.
