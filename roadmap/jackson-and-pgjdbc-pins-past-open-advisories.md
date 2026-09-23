---
id: R970
title: "graphitron-mcp ships Jackson versions with open advisories, and the root pgjdbc pin trails two fixes"
status: Backlog
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

## Likely shape

Import `com.fasterxml.jackson:jackson-bom` and `tools.jackson:jackson-bom` in the root
`dependencyManagement`, at the newest GA patch of the newest GA minor at pickup (2.22.3 and 3.2.3
at filing), and raise `version.postgresql` the same way (42.7.13 at filing). The open question for
Spec is whether the MCP Java SDK tolerates a Jackson 3 minor jump from 3.0 to 3.2. SDK 2.0.1 exists
and may carry a newer Jackson 3 of its own, which would make the SDK bump the lighter fix for that
line. A root Jackson 2.x BOM would also reach `graphitron-sakila-example`. That module imports the
Quarkus BOM in its own `dependencyManagement`, which should take precedence over the parent's and
keep the version Quarkus tests its extensions against; Spec confirms this on the resolved tree.
