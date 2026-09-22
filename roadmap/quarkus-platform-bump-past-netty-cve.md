---
id: R964
title: "The example consumer's Quarkus platform pins a Netty carrying a published SNI routing bypass"
status: Backlog
bucket: cleanup
priority: 5
theme: tooling
depends-on: []
created: 2026-09-22
last-updated: 2026-09-22
---

# The example consumer's Quarkus platform pins a Netty carrying a published SNI routing bypass

## Goal

`graphitron-sakila-example` builds against a Quarkus platform whose BOM (the bill of materials
Quarkus publishes to pin one mutually-tested set of versions for every library it ships) carries
Netty 4.1.137.Final or later, so CVE-2026-75595 stops appearing on the reactor's dependency tree.
Nothing a consumer installs changes, because this is the one module we never publish; what changes
is that a scanner pointed at the repository stops reporting a critical finding against us, and
whoever reads that report stops having to re-derive the same not-exploitable verdict by hand.

## Why this is not urgent

The advisory is real but its preconditions are absent here, and the item is filed at priority 5 on
that basis rather than as a live exposure.

Netty is not ours to pin. It arrives only in `graphitron-sakila-example`, transitively, through
`quarkus-vertx` and `quarkus-virtual-threads` to `vertx-core` and from there to `netty-handler`.
No other module in the reactor has `io.netty` on its tree.

That module sets `maven.deploy.skip`, so it is never published: it exists to compile emitted sources
at release 17 and to host the execution-tier demo. There is no Dockerfile in the repository and no
workflow that deploys it, so the vulnerable code never runs anywhere but a CI test.

The bypass also needs a deployment that terminates TLS with Netty, selects the `SslContext` per SNI
name, treats that selection as the only client-certificate gate, and leaves the fallback context
permissive. The module's `application.properties` configures a Postgres datasource and nothing else.
There is no TLS configuration anywhere in the tree, so the example serves plain HTTP and Netty never
reaches the affected code path.

## Implementation

Raise `quarkus.platform.version` in `graphitron-sakila-example/pom.xml` from 3.34.5 to at least
3.38.3. The fix landed in Netty 4.1.137.Final and 4.2.17.Final, and the Quarkus 3.34.x line never
picks it up, so a patch-level bump within our current minor is not an option. What each platform
release carries, read off the published BOMs at filing time:

[cols="1,1"]
|===
| quarkus-bom | netty-handler

| 3.34.5 (ours) | 4.1.132.Final
| 3.34.7 | 4.1.132.Final
| 3.35.4 | 4.1.133.Final
| 3.36.3 | 4.1.135.Final
| 3.37.4 | 4.1.136.Final
| 3.38.3 | 4.1.137.Final
| 3.39.4 | 4.1.138.Final
|===

Re-read the current releases at pickup rather than trusting this table; it is a snapshot, and the
floor it establishes (3.38.3) is the load-bearing part.

Four or five minor versions of Quarkus is a wider jump than the Netty coordinate alone suggests, and
the module pulls `quarkus-rest`, `quarkus-rest-jackson`, `quarkus-jsonb`, `quarkus-jdbc-postgresql`,
`quarkus-agroal` and `quarkus-hibernate-validator` from the same BOM. Expect the cost to sit in
whatever those surface, not in the edit.

Two things worth knowing before picking this up. The `quarkus:build` goal on this module has an
intermittent ArC unused-bean-removal failure tracked as R550, whose own body records that upgrading
Quarkus does not fix it; do not read a green run here as evidence about that item, or a red one as
evidence against this one. And `graphitron-jakarta-rest` compiles at release 17 against
vendor-neutral Jakarta EE 10 spec APIs that are pinned independently of this BOM, so the bump must
not drag that module's compile target or its API versions along with it.

## Tests

The verification build is the acceptance evidence: `mvn install` covers this module's
`quarkus:build` and its execution-tier tests, which is where a platform regression would surface.
Confirm the goal directly with `mvn dependency:tree -Dincludes='io.netty:*' -pl graphitron-sakila-example`
and read the resolved `netty-handler` version off it.

## Other solutions we've considered

Pinning `io.netty` ourselves, by declaring the fixed version in the module's `dependencyManagement`
ahead of the `quarkus-bom` import so the first declaration wins. It is a smaller diff and it moves
only the coordinate the advisory names. Rejected because it diverges the example from the version
set Quarkus tested together, which is most of what the platform BOM is for, and this module's job is
to be an honest example of how a consumer builds. Worth reaching for only if the platform bump
proves expensive and the finding has to clear sooner.

## Provenance

Filed after a dependency warning about CVE-2026-75595 (GHSA-c4c3-7fpv-j4q5) was raised against
graphitron. The assessment above was done at filing. Note that the warning did not come from this
repository's own Dependabot, which has never opened a Netty alert; whoever reported it should be
asked which scanner produced it, and in particular whether it was pointed at a consumer of
graphitron rather than at graphitron itself. A consumer running Quarkus behind per-SNI mTLS would
get a different answer than the one this item records.
