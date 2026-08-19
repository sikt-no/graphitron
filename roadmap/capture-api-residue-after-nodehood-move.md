---
id: R730
title: "The javadoc reference gate reaches test sources, and the capture-API residue it missed"
status: Spec
bucket: cleanup
theme: tooling
priority: 6
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# The javadoc reference gate reaches test sources, and the capture-API residue it missed

The reactor has a build gate whose whole purpose is to stop a `{@link}` from naming a symbol that no
longer exists. It cannot see test sources. This item fixes the eight sites that proved it, then closes
the gap that let them ship.

Two facts make the gap worth an item rather than a sweep. The gate is the reason this project prefers
`{@link}` over prose in the first place, so a habitat where links rot silently undermines the
convention wherever that habitat is load-bearing, and test javadoc here carries a great deal of the
reasoning. And the gap is invisible from the inside: every attempt to measure it that this spec tried
first reported zero findings while quietly documenting the wrong source root, so an implementer who
trusts a green build learns nothing.

## What the residue is

R711 removed a nodehood predicate parameter from every capture entry point, because deciding nodehood
needs the jOOQ catalog and a crawler answering for the SDL corpus may not read it. The removal is
complete in the code that runs. Eight files still name the parameter in prose or in an import.

Seven carry an `import no.sikt.graphitron.rewrite.NodeDeclaration;` with no other mention of the type
in the file, so the import is dead:

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/capture/SdlFactCapture.java:25`
- `graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/StoreFixture.java:11`
- `graphitron-mcp/src/test/java/no/sikt/graphitron/mcp/StoreFixture.java:7`
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/capture/FactCaptureAgreementTest.java:25`
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/capture/PersistentStoreTest.java:5`
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/derive/TypeBackingClassTest.java:5`
- `graphitron/src/test/java/no/sikt/graphitron/rewrite/diagnostics/DiagnosticFactsTest.java:36`

The eighth is the one that motivates the rest.
`graphitron/src/test/java/no/sikt/graphitron/rewrite/capture/WarmStartRefreshTest.java:296` documents
`aWarmRefreshOverAMultiPackageCatalogCompletes` with a `{@link}` whose target is a
`FactCapture#capture` overload ending `..., Map, JooqCatalog, List, NodeDeclaration)`. No such overload
exists. That file needs two edits, the link and then the import it was the last reader of.

Note which of the two problems has an enforcer and which does not. A dead import is not a javadoc
reference issue, so no configuration of the existing gate would ever have flagged the seven; nothing
in this build catches an unused import at all. Only the eighth site is a gate escape. Sweeping the
seven is hygiene the same commit can afford, not evidence about the gate.

## Why nothing caught the eighth

The gate lives in the root pom as the `check-link-references` execution of `maven-javadoc-plugin`,
bound to `verify`, running doclint's `reference` group with `failOnError` left at its default so a
dangling link reddens the build. Its own comment states the stakes: javac ignores javadoc, so without
this a dangling `{@link}` compiles clean and ships.

It runs the plugin's `javadoc` goal. That goal documents main sources. Test sources are the `test-javadoc`
goal's subject, and that goal has no execution here, so `graphitron/src/test/java` has never been
looked at by anything that resolves a reference.

## What extending it costs, measured

The Backlog body deferred this question on the guess that extending the gate would cost real
wall-clock and surface a large pre-existing backlog. Both halves of the guess are wrong, and the
measurement is recorded here so the implementer starts from a number instead of re-deriving one.

**Two traps make a naive implementation pass vacuously, and both fail by reporting zero.** They are
the reason this section exists at all.

The first is `pom.xml:349`. A `pluginManagement` block pins
`<sourcepath>${project.build.sourceDirectory}</sourcepath>` for the whole plugin, deliberately, so
that the release profile's javadoc jar shares the gate's parse. An explicit `sourcepath` overrides
whatever source roots a goal would otherwise choose, so `test-javadoc` documents `src/main/java` and
emits nothing about the test tree. Invoking the goal with the gate's own flags produced a clean run
and a directory of main-source documentation; the tell was the generated `argfile`, which listed 455
main sources and zero test sources. A `-Dsourcepath` on the command line does not fix it either, since
explicit plugin configuration outranks a user property. The execution needs its own `<sourcepath>`
naming the test root.

The second is the `show` level. JUnit classes and methods here are package-private by convention, and
javadoc's default level documents protected and public only, so the test tree's javadoc is never
parsed and doclint never sees a link inside it. This is the same mechanism a previously shipped item
already recorded for private main members, whose dangling link sat invisible to this gate for the same
reason. The execution needs `show` at `private`.

**With both avoided, the backlog is eleven references and one module.** Measured by running javadoc
directly over each module's test tree with `-Xdoclint:reference -private`, main types resolved from
bytecode:

- `graphitron`: 479 test files, 11 dangling references
- `graphitron-lsp` (77 files), `graphitron-mcp` (31), `graphitron-model` (26), `graphitron-javapoet`
  (23), `graphitron-maven-plugin` (16): clean
- the remaining modules have no test tree

One of the eleven is this item's own. The other ten are pre-existing, and they sort into four kinds,
every one a single-line edit:

- **A simple name the file never imported.** `StubbedVariantPipelineTest:15` writes
  `{@link TypeFetcherGenerator#STUBBED_VARIANTS}` with no import of `TypeFetcherGenerator`. The field
  is alive at `TypeFetcherGenerator.java:360`, so the main-source links to it resolve; only the
  unimported spelling fails. Fix: add the import.
- **A target that was deleted.** `RecordFieldAccessorValidationTest` is named twice and no file of
  that name exists; `GraphQLRewriteGenerator#run()` names a method the class does not declare.
- **Signature drift**, which is the eighth site above.
- **Prose that leaked inside the braces.**
  `{@link no.sikt.graphitron.rewrite.generators.the renderer's multiset arm}` is a typo rather than a
  reference.

Each fix takes the choice CLAUDE.md already prescribes: repoint the link at the current symbol, and
downgrade to `{@code}` only where the target genuinely is not a resolvable symbol.

**Cost is about five seconds.** That is raw javadoc over `graphitron`'s 479 test files, the largest
tree in the reactor. The plugin adds a per-module process fork on top, and only six modules have a
test tree, so the realistic addition is well under the main gate's own cost rather than a doubling of
it.

## Design

Two slices. The first is independent of the second and can land alone.

### Slice one: sweep the residue

Delete the seven dead imports. Correct `WarmStartRefreshTest`'s link to the current `capture`
signature and drop the import it no longer needs. Nothing else changes, no assertion moves, and the
verification is that the reactor stays green.

### Slice two: the gate reaches test sources

Add a second `maven-javadoc-plugin` execution beside `check-link-references`, running `test-javadoc`
at `verify` with the same `reference` doclint group, plus the two settings the traps demand: a
`<sourcepath>` naming the test root, and `show` at `private`. Then fix the ten pre-existing references
it surfaces.

Three details the implementer should not have to rediscover. The existing execution's
`detectOfflineLinks` is off so cross-module apidoc probes are not attempted, and the new one wants the
same. `failOnWarnings` stays unset for the same reason it is unset today, that it would fail on
incidental warnings outside this gate's scope. And the modules that opt out of the gate today with
`maven.javadoc.skip` in their own pom (`roadmap-tool`, `graphitron-sakila-example`) keep opting out,
the property covering every execution of the plugin.

Whether slice two belongs to this item or to its own is the reviewer's call. It is kept here because
its entire motivation is slice one's finding, and separating them would leave the measurement above
stranded in a closed item.

## The non-vacuity proof is part of the deliverable

Both traps fail silently by reporting zero findings, which means a green build after slice two is
indistinguishable from a gate that documented the wrong source root. So the slice does not land on a
green build. It lands on a demonstration: plant a dangling `{@link}` in a test source, observe the
build go red and name that file, then remove it. Record in the commit message which file was used and
what the failure said.

The same reasoning covers the `show` level specifically, since the two failures are independent. A
gate configured with the right source root but the default `show` level would still report zero, so
the planted link has to sit inside a package-private test class, which is to say a perfectly ordinary
one, rather than in a public helper.

## Out of scope

- Unused imports as a class of defect. Nothing in this build catches one, and the seven swept here are
  swept because they are in hand rather than because a rule found them. A general enforcer is its own
  item and would want a linter rather than a javadoc gate.
- The main-source gate's `show` level. Private main members sit below it today, which is a real hole a
  previously shipped item already recorded, and widening it would surface a backlog this item has not
  measured. Slice two raises the level for the test execution alone.
- Anything about nodehood, capture strata, or the views R711 landed. The parameter is gone from the
  code that runs; what is left is prose.
