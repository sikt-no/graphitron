---
id: R1021
title: "The javadoc reference gate renders 566 MB of HTML to check links javac checks while compiling"
status: Backlog
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# The javadoc reference gate renders 566 MB of HTML to check links javac checks while compiling

## Goal

The check that a `{@link}` or `{@see}` names a live symbol stops running as a javadoc HTML build at
`verify` and runs inside javac instead, as `-Xdoclint:reference`. A clean build stops spending about
130 seconds on its critical path rendering documentation nobody reads, the check reaches test sources
without a second javadoc execution, and it re-runs whenever the compiler recompiles rather than only
when the javadoc plugin's stale check lets it.

## What the gate costs today

Measured 2026-10-12 at `86a38bf` on a 4 vCPU web sandbox: one `mvnd clean install -Plocal-db`,
green, 22:48 wall clock, profiled with the JFR Maven extension (the `build-profile` skill's method,
with the recording started through `jcmd` because mvnd's agent refuses `-XX:StartFlightRecording`).

* `javadoc:javadoc-no-fork` (`check-link-references`) summed to **189.9 s** across the reactor.
* **131.3 s of it is `graphitron-model`**, and that module sits on the build's critical path: the
  reactor under mvnd is one dependency chain, `graphitron-model`, then `graphitron`, then
  `graphitron-maven-plugin`, then `graphitron-sakila-example`, and those four spans add up to the
  whole wall clock. Every second off that goal is a second off the build.
* The rest: `graphitron-lsp` 18.3 s, `graphitron-mcp` 11.9 s, `graphitron` 9.4 s,
  `graphitron-fixtures-codegen` 4.7 s, the remaining modules a few seconds each.
* What `graphitron-model`'s 131 s produces: **566 MB of HTML in 3,663 files** under
  `target/reports/apidocs`, for 208 hand-written sources and 1,214 generated jOOQ sources (the
  module's execution adds `${model.generated.sources}` to the sourcepath). The gate's only output
  anyone reads is its exit code.

R733's fourth pass priced the gate at 21.6 s for a whole build. The two figures do not conflict: the
plugin is stale-checked and skips on a warm `target/`, so it costs almost nothing on an incremental
build and its full price on a clean one, which is what CI and the verification build run.

## javac already has this check

R492, which added the gate, rests on the premise that javac ignores javadoc. It does by default,
but javac runs the same doclint as the javadoc tool when given `-Xdoclint`, and reports a
`reference` finding as a compile error.

Measured by running javac directly over `graphitron-model`'s 1,422 sources (hand-written plus
generated) against the module's resolved compile classpath, `--release 25 -proc:none`:

| javac invocation | wall |
|---|---:|
| no doclint | 25.3 s, 26.4 s |
| `-Xdoclint:reference/protected` | 27.5 s, 28.5 s |
| `-Xdoclint:reference/private` | 28.9 s |

So about 2 to 3 seconds inside a compile that runs anyway, against 131 s for the javadoc goal. The
tree is clean at both access levels for `graphitron-model`'s main sources, generated jOOQ code
included.

**Non-vacuity, checked.** A copy of `StoreProse.java` was planted with a public member documented
`{@link NoSuchTypeAnywhere#gone()}` and a private one documented `{@link AlsoMissing}`, and swapped
into the same source list. At `/protected` javac exited 1 with `error: reference not found` on the
public link only; at `/private` it reported both. `/protected` therefore matches what today's gate
sees (javadoc's default `show` level), and `/private` closes the private-member hole R730 records.

## Direction for whoever specs this

Add `-Xdoclint:reference/<level>` beside `-Xlint:all` and `-Werror` in the root pom's
`maven-compiler-plugin` `compilerArgs`, so it applies to every module's main and test compile, and
retire the `check-link-references` execution. The release profile's javadoc jar is a separate
execution and is unaffected.

What falls out of that, each wanting confirmation at Spec rather than assumption:

* **Test sources are covered by the test compile.** That is R730's slice two (Ready), delivered
  without the second javadoc execution and without the two vacuous-pass traps that item documents
  (the pinned `sourcepath`, and the `show` level hiding package-private JUnit classes). R730
  measured 11 dangling references in `graphitron`'s test tree at the private level; those have to be
  fixed in the same change, or the level for test compiles chosen with them in view. The two items
  want ordering against each other rather than both landing.
* **R568's false green goes away.** Its subject is the plugin's stale check, which compares the
  option string and the file list but not file content, so an edited body with a new dangling link
  is skipped. maven-compiler-plugin's default incremental mode recompiles a module's whole source
  set when it detects any change, so doclint sees every file whenever any file changed. Confirm that
  with a planted link on a warm `target/`, the way R568 found its hole, before calling R568 settled.
* **Reach widens to modules that opt out today.** `graphitron-sakila-example` and `roadmap-tool`
  set `maven.javadoc.skip`, which a compiler argument does not read. For `graphitron-sakila-example`
  that would put doclint over generated sources compiled at release 17, which is either a useful
  check on emitted javadoc or noise; decide which, and opt the module out explicitly if noise.
* **Generated sources compiled in the same javac run** (`graphitron-model`'s jOOQ classes) are
  clean today. If a jOOQ upgrade changes that, `-Xdoclint/package:` can exclude the generated
  packages without dropping the hand-written ones.

Open question for the Spec: whether the javadoc tool resolves any reference form javac's doclint
rejects, or the reverse, in this tree. The planted case says the common form behaves the same; the
full reactor at `/protected` going green with the javadoc execution removed, and red again with one
planted link per habitat (main, test, private member), is the evidence the change should land on.
