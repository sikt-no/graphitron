---
id: R707
title: "JooqRecordServiceParamPipelineTest asserts on generated helper body strings"
status: Backlog
bucket: tech-debt
priority: 3
theme: testing
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# JooqRecordServiceParamPipelineTest asserts on generated helper body strings

`JooqRecordServiceParamPipelineTest` matches literal fragments of generated method bodies through two
private helpers, `methodBody(spec, name)` and `singularHelperBodies(spec)`, both of which render a
`MethodSpec` to text and hand it to `assertThat(...).contains(...)`. The helper-routing arms are where
it concentrates: `contendedSingularShapes_emitTwoDistinctHelpers_andEachFetcherRoutesToItsOwn`,
`contendedListShapes_emitTwoDistinctListHelpers_andEachFetcherRoutesToItsOwn` and
`identicalShapesAcrossDifferentInputTypes_collapseToOneBareHelper` each assert on
`createFilmRecordN(env.getArgument(` appearing (or not appearing) in a fetcher body, and one arm
counts how many rendered helper bodies contain the string `RELEASE_YEAR`.
`docs/architecture/explanation/development-principles.adoc` bans code-string assertions on generated
method bodies "at every tier", and `docs/architecture/how-to/testing.adoc` repeats the ban for the
pipeline tier this file sits in.

Filed at the member-axis sibling's Done gate, where the same shape was copied into
`InputBeanGroupingPipelineTest` and then removed on review. That removal is worth reading first
because it shows what the replacements look like in this neighbourhood: a descent's declaration order
became a compile error at a `graphitron-sakila-example` fixture, an unchecked cast turned out to be
already enforced by that module's `-Xlint:all -Werror` compile of the emitted tree, and the remaining
pins moved onto the resolved carriers instead of the rendered text.

What makes this file harder than that one, and the reason it is filed rather than fixed in passing: the
thing it wants to pin is genuinely a *call-site routing* fact ("this fetcher calls that helper"), which
has no carrier of its own today. The rendered body is the only place the routing is currently visible,
so the item has to decide where routing becomes observable before it can delete the scans. Candidates
worth weighing: a structural assertion over the `MethodSpec`'s emitted call sites if the javapoet model
exposes enough to name them, a carrier on the fetcher-generation side that records helper-name-per-arg
so the pipeline tier can assert on it, or an execution-tier round-trip through two contended shapes
where a mis-routed helper produces a visibly wrong record. The `RELEASE_YEAR` count is the easiest
half: which columns a helper writes is already a property of the binding shape, so it can be asserted
before emit.

Adjacent, same rule, different files: R669 (`RootLauncherRendererTest`), R554 (the
`TypeSpecAssertions` body-scan helpers), R522 (whether a "seam pin" is a legitimate exception to the
ban at all). R522 in particular should probably be settled first, since a seam-pin carve-out would
cover part of this file's routing assertions and change how much of it needs to move.
