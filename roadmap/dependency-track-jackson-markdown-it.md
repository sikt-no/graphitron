---
id: R1052
title: "Clear the Dependency-Track findings on Jackson 2.22.2 and markdown-it 14.3.0"
status: Backlog
bucket: bug
priority: 2
theme: tooling
depends-on: []
created: 2026-10-16
last-updated: 2026-10-16
---

# Clear the Dependency-Track findings on Jackson 2.22.2 and markdown-it 14.3.0

## Goal

The reactor's Dependency-Track project (SBOM of 2026-10-16) shows no open findings: no module resolves Jackson 2.x below 2.22.3, and the GraphiQL playground that `graphitron-jakarta-rest` serves no longer bundles a `markdown-it` with quadratic linkify handling.

## Findings

Five advisories, on two sources.

- **Jackson 2.22.2, four highs**: GHSA-p6pp-m3f8-5c89 and GHSA-7hhh-6rmp-j9qf on `jackson-core`, GHSA-wv8q-qhhj-9h54 and GHSA-cxp5-3px4-pw24 on `jackson-databind`. The databind advisories name 2.22.3 as the patched release on the 2.22 line, and the same SBOM carries `jackson-core` 2.22.3 without a finding. The root pom already imports `jackson-bom` 2.22.3, and `graphitron-mcp` and `graphitron-maven-plugin` resolve 2.22.3. The 2.22.2 comes only from `graphitron-sakila-example`, whose own `dependencyManagement` imports `quarkus-bom` 3.39.4, which pins the whole Jackson 2.x family (`jackson-core`, `jackson-databind`, `jackson-datatype-jdk8`, `jackson-datatype-jsr310`, `jackson-module-parameter-names`) at 2.22.2 and wins over the inherited import. No Quarkus release pins 2.22.3: 3.39.5 still pins 2.22.2, and 3.40.1 moves to 2.21.7, which the databind advisories list as patched but which the core advisories do not settle.
- **`markdown-it` 14.3.0, one medium**: GHSA-253c-mchw-3w2r, two quadratic paths when `linkify: true`. It reaches a consumer: the committed GraphiQL bundle under `graphitron-jakarta-rest/src/main/resources/no/sikt/graphitron/jakarta/rest/graphiql/` constructs its renderer with `linkify:!0`. `markdown-it` is a transitive of `graphiql` in `graphitron-jakarta-rest/tools/graphiql-build/package-lock.json`, declared as `^14.1.0`, so 14.3.3 (the `v14-legacy` tag, published 2026-10-16) satisfies it. Measured with the advisory's payloads at N=10000: 14.3.0 takes 5702 ms and 805 ms, 14.3.3 takes 294 ms and 26 ms.

## Proposed fix

1. In `graphitron-sakila-example/pom.xml`, import `com.fasterxml.jackson:jackson-bom:${version.com.fasterxml.jackson}` ahead of `quarkus-bom`, so the module follows the root's Jackson pin instead of the platform's. A patch bump inside the 2.22 line the platform already chose. Acceptance: `dependency:tree` on the module shows no Jackson 2.x below 2.22.3.
2. In `graphitron-jakarta-rest/tools/graphiql-build`, move the lockfile's `markdown-it` to 14.3.3 and rerun `npm run build`, committing the regenerated playground resources. Acceptance: the lockfile and the rebuilt bundle carry 14.3.3, and the module's existing playground tests stay green.
