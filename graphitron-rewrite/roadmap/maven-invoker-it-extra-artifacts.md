---
id: R111
title: "Make graphitron-maven-plugin IT self-contained via extraArtifacts"
status: Backlog
bucket: cleanup
depends-on: []
---

# Make graphitron-maven-plugin IT self-contained via extraArtifacts

The Rewrite reactor CI workflow runs `mvn ... verify -Plocal-db`, which never installs sibling reactor modules into `~/.m2`. When `maven-invoker-plugin:run` forks a separate Maven invocation for each IT under `graphitron-maven-plugin/src/it/` (`basic-generate`, `missing-schema-inputs`), that child build cannot resolve `no.sikt:graphitron-sakila-db:10-SNAPSHOT`, which the IT poms declare as a plugin dependency. The result is two failed IT builds and a red `Rewrite reactor CI` check. The `maven-invoker-plugin:install` goal only seeds the IT local repo with the project under test (`graphitron-maven-plugin`) and its declared dependency tree, so artifacts referenced only from the IT poms slip through. Local builds mask the problem because the documented developer workflow uses `mvn install -Plocal-db`, which populates `~/.m2`. The fix is to declare the missing reactor artifact via `<extraArtifacts>` on the maven-invoker-plugin configuration in `graphitron-rewrite/graphitron-maven-plugin/pom.xml`, keeping the seam scoped to the IT execution rather than altering the top-level lifecycle, and to update the comment in `src/it/settings.xml` that currently asserts external-prior-install as the contract.
