---
id: R964
title: "The example consumer's Quarkus platform pins a Netty carrying a published SNI routing bypass"
status: In Progress
bucket: cleanup
priority: 5
theme: tooling
depends-on: []
created: 2026-09-22
last-updated: 2026-09-23
---

# The example consumer's Quarkus platform pins a Netty carrying a published SNI routing bypass

## Goal

`graphitron-sakila-example` builds against a Quarkus platform whose BOM (the bill of materials
Quarkus publishes to pin one mutually-tested set of versions for every library it ships) carries
Netty 4.1.137.Final or later, so CVE-2026-75595 stops appearing on the dependency tree of every
module in the reactor. Nothing a consumer installs changes, because this is the one module we never
publish; what changes is that a scanner pointed at the repository stops reporting a critical finding
against us, and whoever reads that report stops having to re-derive the same not-exploitable verdict
by hand.

## Why this is not urgent

The advisory is real but its preconditions are absent here, and the item is filed at priority 5 on
that basis rather than as a live exposure.

Netty is not ours to pin. It arrives only in `graphitron-sakila-example`, transitively, through
`quarkus-vertx` and `quarkus-virtual-threads` to `vertx-core` and from there to `netty-handler`.
No other module in the reactor has `io.netty` on its dependency tree.

That module sets `maven.deploy.skip`, so it is never published: it exists to compile emitted sources
at release 17 and to host the execution-tier demo. There is no Dockerfile in the repository and no
workflow that deploys it, so the vulnerable code never runs anywhere but a CI test.

The bypass also needs a deployment that terminates TLS with Netty, selects the `SslContext` per SNI
name, treats that selection as the only client-certificate gate, and leaves the fallback context
permissive. The module's `application.properties` configures a Postgres datasource and nothing else.
There is no TLS configuration anywhere in the tree, so the example serves plain HTTP and Netty never
reaches the affected code path.

## Which platform version

The rule, which is the load-bearing part of this section: land on the newest generally-available
patch of the newest generally-available minor at pickup, not on the lowest version that clears the
advisory. Everything below is the evidence for that rule, and is a snapshot.

The floor is 3.38.3: the fix landed in Netty 4.1.137.Final and 4.2.17.Final, and the Quarkus 3.34.x
line never picks it up, so a patch-level bump within our current minor is not an option. What each
platform release carries, read off the published BOMs on Maven Central at spec time:

[cols="1,1,1"]
|===
| quarkus-bom | netty-handler | released

| 3.34.5 (ours) | 4.1.132.Final | 2026-04-16
| 3.34.7 (last 3.34.x) | 4.1.132.Final | 2026-05-04
| 3.35.4 | 4.1.133.Final | 2026-05-20
| 3.36.3 | 4.1.135.Final | 2026-06-17
| 3.37.4 | 4.1.136.Final | 2026-07-22
| 3.38.3 | 4.1.137.Final | 2026-08-19
| 3.39.4 | 4.1.138.Final | 2026-09-16
|===

The release dates are why the floor is the wrong target. Quarkus opens a minor line roughly monthly
(3.34.0 on 2026-03-18, 3.35.0 on 04-22, 3.36.0 on 05-20, 3.37.0 on 06-17, 3.38.0 on 07-22, 3.39.0 on
08-19) and stops patching the previous one within a couple of weeks of the successor shipping:
3.34's last patch, 3.34.7, is dated 2026-05-04, twelve days after 3.35.0 opened. Landing on 3.38.3
would put us on a line that stopped receiving fixes a month ago and would guarantee another bump
cycle, with the same regression surface, at the next advisory. It would also hand a consumer reading
this module as a worked example a platform line that is no longer patched, which is not what the
module is for.

At spec time the rule resolves to 3.39.4, which is what the dry run below measures. Re-read the
versions at pickup rather than trusting the table: 3.40.0.CR1 was published on 2026-09-16, so a
3.40.x GA release may well be the right target by then. Take GA only; a `.CR` or `.Beta` qualifier is
a release candidate, not something to build an example consumer on.

One edit covers both halves of the platform. `quarkus.platform.version` in
`graphitron-sakila-example/pom.xml` is read twice, by the `quarkus-bom` import in the module's
`dependencyManagement` and by the `quarkus-maven-plugin` version in its build, so the BOM and the
plugin that consumes it cannot drift apart.

## What the bump actually moves

Less than a four-or-five-minor jump suggests, and the reason is worth stating because it is
counter-intuitive and it bounds the review.

The root pom's `dependencyManagement` outranks the BOM the child module imports. Maven gives an
explicitly declared managed version precedence over one contributed by an import-scoped BOM,
inherited declarations included, so every coordinate the reactor pins centrally keeps its pinned
version in this module no matter what the platform says. Read off the module's effective POM
(`mvn -pl graphitron-sakila-example help:effective-pom`) against the 3.34.5 BOM: jOOQ resolves
3.20.11, graphql-java 25.0 where the BOM offers 24.3, JUnit 6.0.3, Testcontainers 2.0.4, PostgreSQL
42.7.10, AssertJ 3.27.7, Logback 1.5.32, `jakarta.validation-api` 3.1.1, every one of them the root
pom's value. The graphql-java upgrade item and this one therefore do not interact in either
direction.

What moves is the complement: the coordinates Quarkus manages and we do not. Netty is the one we are
after. The rest is Vert.x and the RESTEasy Reactive and SmallRye stacks that carry it, Jackson
(2.21.2 to 2.22.0), the JSON-B provider, and one test-scope coordinate that deserves naming on its
own:

* **rest-assured 5.5.6 to 6.0.1**, a major version, and the only moving coordinate with our own
  source sitting on top of it. Four `@QuarkusTest` classes use it (`GraphqlResourceSmokeTest`,
  `GraphQLOverHttpConformanceTest`, `MountedEndpointTest`, `OverlappingMountTest`), all through the
  static `given()` entry point and the `given()...then()` chain, which is the most stable part of
  that API. The dry run below resolved 6.0.1 and ran all four unchanged, so this costs nothing; it
  is named because those four classes are the conformance harness for `graphitron-jakarta-rest`'s
  HTTP status-code and wire semantics rather than incidental tests, which is what makes them worth
  watching the next time a platform moves that major.
* **The JSON-B provider relocates.** 3.34.5 manages `org.eclipse:yasson`; the newer BOMs exclude that
  coordinate and manage `org.eclipse.yasson:yasson` instead. Nothing in our tree names either
  coordinate, so there is nothing to do, but a hand-written exclusion or pin naming the old group
  would have silently stopped matching, and this is where to look first if the JSON-B path in
  `graphitron-jakarta-rest` misbehaves after the bump.

## What the bump does not clear

A vulnerable Netty stays in the tree after this item lands, in a place the platform pin cannot
reach, and the goal above is written about module dependency trees for that reason.
`graphitron-docs` resolves `asciidoctor-maven-plugin` 3.2.0, whose own pom pins
`io.netty:netty-codec-http:4.1.119.Final` for its preview HTTP server, which drags `netty-handler`
4.1.119.Final into that plugin's realm. 3.2.0 is the newest release of the plugin, so there is
nothing to bump to.

It is a plugin realm, not a dependency tree: nothing in it is on any module's compile, runtime or
test classpath, and the module binds only the `process-asciidoc` goal, so the preview server the
netty is there for never starts. The not-exploitable argument is stronger there than in the example
module, not weaker. If the scanner behind this item turns out to read plugin realms, clearing it is a
`netty-codec-http` override in the `<plugin><dependencies>` block `docs/pom.xml` already carries for
`asciidoctorj`, and that is a separate item: it pins a combination the plugin's authors did not test,
against a build-time renderer, for a goal this item does not need it for.

## Dry run

The findings below are measured on a throwaway worktree at 3.39.4, not predicted. A full
`mvn clean install -Plocal-db -T 1C` ran the whole reactor in about 25 minutes of wall clock.

* **The goal holds at 3.39.4.** `netty-handler` resolves 4.1.138.Final in
  `graphitron-sakila-example`, past the 4.1.137.Final fix.
* **rest-assured needs no call-site edits.** 6.0.1 resolved and all four conformance classes passed
  unchanged: `GraphqlResourceSmokeTest` (6 tests), `MountedEndpointTest` (12), `OverlappingMountTest`
  (3), `GraphQLOverHttpConformanceTest` (16).
* **Nothing else in the module needed changing.** Emitted sources still compile at release 17 under
  the inherited `-Xlint:all -Werror`, the Quarkus `generate-code` and `build` goals ran, and the ArC
  removal flake did not appear, which per *Out of scope* is evidence about neither item.
* **Deleting the two root management entries is clean.** With them gone, `hibernate-validator`
  resolves 9.1.3.Final, the version the 3.39.4 platform tested its extension against, instead of the
  9.0.1.Final the root pin holds it at, and the module's 935 tests pass with nothing else changed.
* **One test failed, and it is not this change.**
  `OptionalNodeIdProjectionExecutionTest.anOmittedNodeIdReachesAConditionMethodAsNull` expected film
  ids `[1, 2, 3, 4, 5, 68]` and read `[1, 2, 3, 4, 5]`. Re-running the module on the same tree is
  green (935 tests, 0 failures, 13 skipped), and the failure is the shared-database cross-talk
  tracked as R863: the assertion compares an unfiltered root-field read against a filtered one, which
  is exactly the reader-side violation this module's `junit-platform.properties` names ("readers
  assert what their own query means rather than what a table holds"); the seed holds five films, all
  of language 1, so id 68 is not seeded; and `film_film_id_seq` stands at 87, so sibling classes
  create and delete film rows through that id range for the length of a run. Nothing in the moving
  set can add a row to a jOOQ result. Worth carrying to R863 as a fourth reader that breaks its rule.

Re-measure at pickup if the platform version has moved on, with the same two reads the dry run used:
the effective POM for what the root pom still outranks, and the dependency tree for what resolves.

## Implementation

1. **Raise `quarkus.platform.version`** in `graphitron-sakila-example/pom.xml` per the rule above.

2. **Delete the orphaned `hibernate-validator` and `expressly` entries** from the root pom's
   `dependencyManagement`. They predate this module's move to the `quarkus-hibernate-validator`
   extension and no module declares either coordinate any more; the only mentions left outside the
   root are the two comments in `graphitron-sakila-example/pom.xml` recording that the direct
   dependencies were replaced by the extension. Their one remaining effect is the precedence rule
   above working against us: the root pins `hibernate-validator` 9.0.1.Final, so the implementation
   the extension pulls is held below the version the platform tested it with, in the one module that
   uses it.

   This belongs in this item rather than a follow-up because it is not a neighbouring cleanup: it is
   a version fact sitting under the platform that a platform bump cannot move, which is the shape
   this item exists to clear. Deleting it lets the BOM own the implementation version it manages and
   stops the next platform bump re-deriving the same analysis. If a later platform makes the
   validator move break the module, the fallback is to restore both entries with a comment recording
   that holding the implementation below the platform's tested version is deliberate and why, rather
   than leaving them unexplained as they are now.

Nothing else changes. In particular this item adds no version floor of its own; see *Other solutions
we've considered*.

## Tests

The verification build is the acceptance evidence for the bump itself: `mvn install -Plocal-db`
covers this module's `quarkus:build` and its execution-tier tests, which is where a platform
regression surfaces. The diff touches poms, so the roadmap-only scoped build does not apply. Use
`clean install`, not a warm incremental build: a stale class file compiled against the old platform
produces a `NoSuchMethodError` cascade that looks like a real break and is not, which is the same
trap the graphql-java upgrade item records.

Two reads beyond the green build, because a green build is compatible with the goal being
undelivered. Both are `mvn dependency:tree -pl graphitron-sakila-example`:

* with `-Dincludes='io.netty:*'`, `netty-handler` resolves at or above 4.1.137.Final. This is the
  goal, read directly.
* with `-Dincludes='org.hibernate.validator:*'`, the implementation resolves at the version the
  platform BOM names. This is what step 2 delivers.

If a newer platform than the one measured above does force rest-assured call-site edits, they
preserve each assertion's subject. Those four classes are an enforcer, not incidental tests: a
rewritten assertion that still passes while checking something weaker is a silent loss of the
guarantee it holds. Anything the newer DSL cannot express verbatim gets called out in the commit
rather than relaxed.

## Out of scope

* **The ArC unused-bean-removal flake.** The `quarkus:build` goal on this module intermittently fails
  inside Quarkus ArC's unused-bean removal, tracked as R550, whose own body establishes that
  upgrading Quarkus does not fix it. Do not read a green run here as evidence about that item, or a
  red one as evidence against this one, and do not ride its pin along with this change.
* **`graphitron-jakarta-rest`.** It compiles at release 17 against vendor-neutral Jakarta EE 10 spec
  APIs pinned in the root pom independently of this BOM. The bump must not drag that module's compile
  target or its API versions along with it.
* **The plugin-realm Netty** in `graphitron-docs`, per *What the bump does not clear*.
* **Why the platform went five months stale.** Dependabot proposes this bump and its PRs target
  `main`, which the rewrite does not integrate through (see *Provenance*). Whether the rewrite trunk
  should have an update feed of its own is a real question with a real answer, and it is not this
  item's: this item lands one bump, and a recurrence mechanism is worth its own Backlog entry.

## Other solutions we've considered

Pinning `io.netty` ourselves, by declaring the fixed version in the module's `dependencyManagement`
ahead of the `quarkus-bom` import so the first declaration wins. It is a smaller diff and it moves
only the coordinate the advisory names. Rejected because it diverges the example from the version set
Quarkus tested together, which is most of what the platform BOM is for, and this module's job is to
be an honest example of how a consumer builds. Worth reaching for only if the platform bump proves
expensive and the finding has to clear sooner; the dry run says it does not.

Landing on 3.38.3, the lowest version that clears the advisory, on the reasoning that a smaller jump
is a smaller regression surface. Rejected on the release cadence above: the surface between 3.38 and
3.39 is one minor out of five, while the difference in outcome is between landing on a line that is
still patched and one that is not.

Adding a `bannedDependencies` enforcer rule to the module, banning the Netty ranges the advisory
names, so the floor is build-enforced rather than a fact somebody once read off a dependency tree.
Rejected on two counts. The module's `pom.xml` is the copy-this recipe its README hands consumers,
and an honest consumer pom pins a platform rather than carrying one project's snapshot of one 2026
advisory. And the rule would be green for the wrong reason: the platform pin sits far above the floor
by construction, so the only thing the range can catch is a deliberate one-line downgrade in the file
the rule itself sits in, while at the next Netty advisory it would still assert a superseded floor
and pass. An enforcer that stays green after the invariant it names has moved is worse than none,
because the green reads as coverage.

Waiting for Dependabot to propose it. Rejected because it already did and the PRs are closed; see
*Provenance*.

## Provenance

Filed after a dependency warning about CVE-2026-75595 (GHSA-c4c3-7fpv-j4q5) was raised against
graphitron. The assessment above was done at filing. Note that the warning did not come from this
repository's own Dependabot, which has never opened a Netty alert; whoever reported it should be
asked which scanner produced it, and in particular whether it was pointed at a consumer of graphitron
rather than at graphitron itself. A consumer running Quarkus behind per-SNI mTLS would get a
different answer than the one this item records. Which Netty version the report named is worth asking
for too: 4.1.132.Final is the example module's, 4.1.119.Final is the docs plugin realm's, and the two
say different things about what the scanner reads.

The reason we are five months behind on a monthly-cadence platform is procedural rather than
technical, which matters because it means there is no prior evidence of a breaking bump to reckon
with. Dependabot opened [#470](https://github.com/sikt-no/graphitron/pull/470) (3.34.5 to 3.35.0),
[#483](https://github.com/sikt-no/graphitron/pull/483) (3.35.0 to 3.35.1) and
[#490](https://github.com/sikt-no/graphitron/pull/490) (3.35.1 to 3.35.2) between April and May, all
three against `main`, and all three were closed unmerged on 2026-07-02 with the note that the rewrite
integrates through the `claude/graphitron-rewrite` trunk rather than through PRs against `main`. None
of them failed a build, and none of them would have reached this trunk if merged. Dependabot has
opened none since. The bump is ours to make by hand on this trunk.
