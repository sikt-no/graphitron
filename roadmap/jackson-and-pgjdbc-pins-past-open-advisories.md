---
id: R970
title: "graphitron-mcp ships Jackson versions with open advisories, and the root pgjdbc pin trails two fixes"
status: In Progress
bucket: cleanup
priority: 3
theme: tooling
depends-on: []
created: 2026-09-23
last-updated: 2026-09-23
---

# graphitron-mcp ships Jackson versions with open advisories, and the root pgjdbc pin trails two fixes

## Goal

The `graphitron-mcp` jar a consumer's `graphitron:dev` JVM loads resolves Jackson 2.x at 2.21.5 or
later and Jackson 3.x at 3.1.5 or later, and the root `version.postgresql` pin sits at 42.7.12 or
later. After that, a dependency scanner (a tool such as Dependency-Track that matches every
resolved library version against published security advisories) reports no open advisory against
anything we publish, and none against the reactor as a whole. Today fourteen advisories arrive
with the Maven plugin and land on every consumer's classpath.

## Implementation record

Shipped as planned under Implementation below, at the Spec's versions:
`version.com.fasterxml.jackson` 2.22.3, `version.tools.jackson` 3.2.3 and `version.postgresql`
42.7.13 (each still the newest GA in Maven Central metadata at pickup), plus the two BOM imports in
the root `<dependencyManagement>`, 2.x first. `CLAUDE.md` states 42.7.13. No module pom or source
changed, and the direct-dependency fallback was not needed.

Acceptance evidence, taken after the verification build on the implementation tree:

- **Consumer path.** `mvn dependency:resolve-plugins -pl graphitron-sakila-example -Plocal-db`: the
  `graphitron-maven-plugin` block lists `tools.jackson.core:jackson-databind`, `jackson-core` and
  `tools.jackson.dataformat:jackson-dataformat-yaml` at 3.2.3, `com.fasterxml.jackson.core:jackson-core`
  and `jackson-databind` at 2.22.3, and `jackson-annotations` at 2.22. The Quarkus plugin's block
  keeps its own 2.22.2.
- **Reactor.** `mvn dependency:tree -Plocal-db -Dincludes='com.fasterxml.jackson*,tools.jackson*,org.postgresql*'`:
  `graphitron-mcp` and `graphitron-maven-plugin` show the same versions as above;
  `graphitron-sakila-example` shows Quarkus's Jackson 2.22.2 (annotations 2.22) and
  `org.postgresql:postgresql` 42.7.13 at test scope. `graphitron-sakila-db`'s jOOQ codegen plugin
  dependency reads `${version.postgresql}` directly.
- **Runtime compatibility.** The verification build (`mvn install -Plocal-db`) passed, including
  `GraphitronMcpServerTest` (57 tests) and `CatalogSearchOnnxTest` in `graphitron-mcp`, and the
  `graphitron-sakila-example` suite (942 run, 0 failures) against the 42.7.13 driver.

Advisory recheck: the GitHub advisory API and OSV are not reachable from the implementing sandbox,
so the chosen versions were checked against the patched versions this body records for each GHSA
id, not against the advisory pages themselves. Every 2.x id is patched by 2.22.1 or earlier on the
2.22 line (2.22.3 chosen), every 3.x id by 3.2.1 or earlier on the 3.2 line (3.2.3 chosen), and
both pgjdbc ids by 42.7.12 (42.7.13 chosen). The reviewer should confirm the ranges on the
advisory pages, or with a fresh Dependency-Track scan, if either is reachable.

## What is open, and where it comes from

A Dependency-Track scan of the reactor, taken before R964 raised the example's Quarkus platform,
listed 56 advisories. Checked against the tree resolved on trunk after R964, 40 of them are fixed
there: all of Netty, Vert.x and `quarkus-vertx-http`. The Quarkus-managed Jackson 2.x in
`graphitron-sakila-example` is also clean (now 2.22.2), though the same Jackson advisory ids stay
open under `graphitron-mcp`. Sixteen remain, in two places.

**Jackson under `graphitron-mcp`: fourteen advisories, in a published artifact.** The module is
published, and `graphitron-maven-plugin` depends on it at compile scope, so both Jackson lines reach
consumers. The root pom manages neither Jackson line; each resolves to whatever version its puller
declares.

- `com.fasterxml.jackson.core` 2.21.3, pulled by `langchain4j-core` 1.16.3 (under
  `langchain4j-embeddings-bge-small-en-v15-q`). Eleven open: GHSA-r7wm-3cxj-wff9 on core, and on
  databind GHSA-j3rv-43j4-c7qm, GHSA-rmj7-2vxq-3g9f, GHSA-rcqc-6cw3-h962, GHSA-3pjw-73gf-8qr5,
  GHSA-9fxm-vc8v-hj55, GHSA-5hh8-q8hv-fr38, GHSA-hgj6-7826-r7m5 (all fixed in 2.21.4), plus
  GHSA-mhm7-754m-9p8w, GHSA-5gvw-p9qm-jgwh and GHSA-5jmj-h7xm-6q6v (fixed in 2.21.5, or 2.22.1 on
  the 2.22 line).
- `tools.jackson.core` 3.0.3, pulled by the MCP Java SDK 2.0.0 (`mcp-json-jackson3`, and
  `json-schema-validator` 3.0.0 under it). Twelve open, nine of them ids shared with the 2.x line.
  On databind: GHSA-j3rv-43j4-c7qm, GHSA-rmj7-2vxq-3g9f, GHSA-rcqc-6cw3-h962,
  GHSA-3pjw-73gf-8qr5, GHSA-9fxm-vc8v-hj55, GHSA-5hh8-q8hv-fr38, GHSA-hgj6-7826-r7m5 (fixed in 3.1.4) and GHSA-5gvw-p9qm-jgwh (fixed in
  3.1.5, or 3.2.1). Open on core: GHSA-r7wm-3cxj-wff9, GHSA-6v53-7c9g-w56r, GHSA-2m67-wjpj-xhg9
  and GHSA-72hv-8253-57qq (fixed by 3.1.4).

Real exposure is low: the MCP server binds to loopback only and runs only during development.
Most of the databind findings need polymorphic typing, `@JsonUnwrapped` or `@JsonView` on the
types being read, and the core findings are denial of service on oversized input. The item is filed
anyway because the artifact ships, so every consumer's scanner reports these against graphitron.

**pgjdbc 42.7.10: two advisories, in unpublished modules.** GHSA-98qh-xjc8-98pq (fixed in
42.7.11) and GHSA-j92g-9f8w-j867 (fixed in 42.7.12). The root `version.postgresql` feeds the jOOQ
codegen plugin in `graphitron-sakila-db` and the test-scope driver in `graphitron-sakila-example`.
Both modules set `maven.deploy.skip`, and neither advisory's preconditions hold here: j92g needs
`channelBinding=require`, and 98qh needs a malicious server during SCRAM authentication. The fix is
the one-line bump, plus the version CLAUDE.md states under Technology constraints.


Two resolved-tree facts sit beside the advisories. `jackson-annotations` resolves to 2.20 under
`graphitron-mcp` (the MCP SDK's `mcp-core` declares it nearer than `langchain4j-core` does), below
the 2.21 that databind 2.21.3 is built against, so the 2.x line is mismatched today as well as
behind. And in `graphitron-sakila-example` the root pin does more than trail: the module's
explicit test-scope `org.postgresql:postgresql` at `${version.postgresql}` is the nearest
declaration, so it overrides the 42.7.13 that `quarkus-jdbc-postgresql` 3.39.4 brings and resolves
42.7.10. Raising the root pin to 42.7.13 makes the two agree.

## Implementation

Every change is in two files, the root `pom.xml` and `CLAUDE.md`. No module pom changes, and no
source changes: nothing in the reactor imports a Jackson type (`graphitron-mcp`,
`graphitron-maven-plugin` and `graphitron-lsp` have no `com.fasterxml.jackson` or
`tools.jackson` import, and no pom names either group), so Jackson is purely a transitive
concern of the MCP SDK and langchain4j.

- **Root `pom.xml`, `<properties>`.** Add `version.com.fasterxml.jackson` and
  `version.tools.jackson`, following the `version.<groupId>` naming the other pins use. Two
  properties rather than one because the lines release independently. Set each to the newest GA
  patch of the newest GA minor at pickup: 2.22.3 and 3.2.3 as of this Spec. Raise
  `version.postgresql` the same way: 42.7.13 as of this Spec.
- **Root `pom.xml`, `<dependencyManagement>`.** Import `com.fasterxml.jackson:jackson-bom` and
  `tools.jackson:jackson-bom` (type `pom`, scope `import`) beside the existing `mcp-bom`,
  `jetty-bom` and `jetty-ee10-bom` imports, with a comment in the style of theirs saying what
  pulls each line (langchain4j for 2.x, the MCP SDK's `mcp-json-jackson3` for 3.x) and that the
  imports exist to lift transitive versions, since no module declares Jackson. Put the 2.x import
  first. Both BOMs manage `com.fasterxml.jackson.core:jackson-annotations` (Jackson 3 keeps using
  the 2.x annotations artifact), both at `2.22` for the versions above, and among imports the
  first declaration wins; ordering the 2.x BOM first makes the line that owns that artifact the
  one that decides it if the two ever disagree.
- **`CLAUDE.md`, Technology constraints.** Update the stated PostgreSQL driver version to the
  new pin.

Three properties of this shape were checked on the tree at Spec time, by applying the two imports
and the pgjdbc bump locally:

- Under `graphitron-mcp` and `graphitron-maven-plugin` the 3.x line resolves to 3.2.3 across
  `jackson-core`, `jackson-databind` and `jackson-dataformat-yaml` (the last pulled by
  `json-schema-validator`), the 2.x line to 2.22.3 for core and databind, and
  `jackson-annotations` to 2.22. The mismatch above goes away with the advisories.
- `graphitron-sakila-example` keeps Quarkus's Jackson 2.22.2. The module imports the Quarkus
  platform BOM in its own `<dependencyManagement>`, which takes precedence over the parent's
  imported BOM, so the example stays on the version Quarkus tests its extensions against. Only
  its pgjdbc changes, to 42.7.13.
- MCP SDK 2.0.0 tolerates Jackson 3.2.3 at runtime. With both imports applied,
  `mvn test -pl graphitron-mcp -Plocal-db` ran 134 tests with no failures, including the 57 in
  `GraphitronMcpServerTest` that drive the live Streamable HTTP transport end to end with the
  SDK's own client, so JSON-RPC serialisation on both sides went through Jackson 3.2.3. The SDK
  bump the Backlog body floated is therefore not needed for the goal (see Other solutions).

What reaches a consumer is the plugin's resolved classpath, not the reactor's. A consumer's Maven
resolves `graphitron-maven-plugin` with the plugin artifact as the root of the graph, and the
root's effective `dependencyManagement` (inherited from the published
`graphitron-rewrite-parent`, BOM imports included) governs versions throughout that graph, so
the imports reach the transitive Jackson edges under `graphitron-mcp`. This is the one property
that the Spec-time run above did not observe directly, because the check needs the plugin
installed with the new parent. The acceptance check under Tests observes it. If it does not hold,
the fallback is to declare `com.fasterxml.jackson.core:jackson-databind` and
`tools.jackson.core:jackson-databind` directly in `graphitron-mcp`'s pom, versionless under the
BOMs: as direct dependencies of a direct dependency of the plugin they are nearer than any
transitive declaration, so nearest-wins selects them whatever the consumer's resolver does with
management. The module comment on the reactor-edge allowlist is unaffected, since neither is a
reactor module.

## Tests

The acceptance evidence is resolved-tree output, recorded in the implementation commit message,
plus the existing suites passing in the verification build.

- **Consumer path.** After the full install, `mvn dependency:resolve-plugins -pl
  graphitron-sakila-example -Plocal-db` lists the `graphitron-maven-plugin` plugin's resolved
  dependencies. In that block, every `com.fasterxml.jackson.core` artifact is at the new 2.x
  version (annotations at `2.22`), and every `tools.jackson.*` artifact is at the new 3.x version.
  Today the same command shows `jackson-core` and `jackson-databind` 2.21.3 and
  `jackson-annotations` 2.20 there. This check is the one that demonstrates the goal, because it
  is the classpath a consumer's `graphitron:dev` JVM loads. Other plugins in the same output
  (the Quarkus plugin) carry their own Jackson and are not this item's concern.
- **Reactor.** `mvn dependency:tree -Plocal-db -Dincludes='com.fasterxml.jackson*,tools.jackson*,org.postgresql*'`
  shows the versions listed under Implementation for `graphitron-mcp` and
  `graphitron-maven-plugin`, Jackson 2.22.2 (Quarkus-managed, unchanged) for
  `graphitron-sakila-example`, and pgjdbc at the new pin for the example's driver.
- **Runtime compatibility.** The `graphitron-mcp` suite, in particular `GraphitronMcpServerTest`
  (the SDK transport over Jackson 3) and `CatalogSearchOnnxTest` (the langchain4j embedder), and
  the `graphitron-sakila-db` codegen plus the `graphitron-sakila-example` execution tier (the
  driver bump) all pass in the verification build. No new test is added: the property at stake is
  a resolved version, which the checks above observe directly, and a test asserting pom versions
  would pin numbers the next bump has to edit without guarding anything the scan does not.

The open-advisory count itself comes from an external scanner the reactor does not run, so the
implementer rechecks the chosen versions against the GHSA ids listed above (each id's advisory
page states its patched ranges) rather than against a build step.

## Other solutions we've considered

- **Bump the MCP SDK to 2.0.1 instead of importing the 3.x BOM.** 2.0.1's `mcp-json-jackson3`
  declares `jackson-databind` 3.1.4 (and `json-schema-validator` 3.0.6, which also declares
  3.1.4). That fixes every 3.x advisory above except GHSA-5gvw-p9qm-jgwh, fixed in 3.1.5, so it
  does not reach the goal on its own, and the BOM import is needed either way. The SDK bump is a
  reasonable separate patch upgrade but is not part of this item; with the BOM in place the SDK's
  own Jackson declaration no longer decides the resolved version.
- **Pin individual Jackson artifacts in `<dependencyManagement>`.** Covers only the artifacts
  named, and the 3.x line alone pulls three (`jackson-core`, `jackson-databind`,
  `jackson-dataformat-yaml`), so the next transitive addition would slip past. The BOM manages
  the whole line coherently, and the reactor already manages the MCP SDK and Jetty that way.
- **Drop `graphitron-sakila-example`'s explicit test-scope pgjdbc and let Quarkus manage the
  driver.** Would remove the downgrade described above for good, but the root property also feeds
  the jOOQ codegen plugin in `graphitron-sakila-db`, so the property stays, and changing how the
  example gets its driver is a separate decision from clearing two advisories.
- **Add a vulnerability scanner to CI** (for instance OWASP dependency-check) so a new advisory
  fails the build instead of arriving by an external scan. That is a standing policy with its
  own false-positive and suppression costs, and belongs in its own item; this one clears what is
  open today.
