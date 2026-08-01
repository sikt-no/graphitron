---
id: R554
title: "Retire the generated-body string-scan helpers in TypeSpecAssertions"
status: Backlog
bucket: test-quality
priority: 3
theme: testing
depends-on: []
created: 2026-07-28
last-updated: 2026-07-28
---

# Retire the generated-body string-scan helpers in TypeSpecAssertions

`TypeSpecAssertions` exists to replace the `assertThat(method.code().toString()).contains(...)`
pattern that `docs/architecture/explanation/development-principles.adoc` bans, but four of its
helpers implement that pattern internally: `hasFieldsArm`, `appendsRequiredColumn`,
`armGuardsArgumentConsistency`, and `serviceChildKeyExtractionIsUnconditional` all scan a rendered
method body for a substring. The file's own javadoc argues this is acceptable because the fragility
is confined to one place, which is a real improvement over scattering the scans across call sites,
but it is a containment argument rather than a structural one: the assertions still break on emitter
formatting changes that alter nothing observable, and a negative scan is worse still, since
`serviceChildKeyExtractionIsUnconditional` asserts the absence of `instanceof` and so silently flips
if any unrelated `instanceof` ever appears in that fetcher method.

The fix is to ask the structural question against the `CodeBlock`/`MethodSpec` tree instead of the
rendered string, so the assertions name emitted structure rather than emitted text. Worth pricing
against the fact that R549's keystone slice retires the projection walk these helpers audit: if that
lands first, some of the family goes away on its own and only the survivors need rehoming.

Raised by the independent Done-gate reviewer on the PK-only service key contract item, which net
removed one member of the family and was explicitly not asked to fix the rest.

Scope extension from the facts-and-commands Done-gate review (2026-08-01): the renderer arm tests
(`ProjectionUnitRendererTest`, `RootLauncherRendererTest`) open-code the same rendered-string scan
(`code().toString()` + `contains(...)`) at their call sites, outside the `TypeSpecAssertions`
containment this item was filed against, and `armProjectsColumn` /
`armGuardsArgumentConsistency` joined the contained family as successors of the retired
`appendsRequiredColumn`. The testing.adoc renderer-arm rubric row blesses "per-arm structural
assertions" but is silent on assertion mechanics; when this item lands the structural
`CodeBlock`/`MethodSpec` form, the renderer arm tests are in scope, and the rubric row should
state the mechanics so the ambiguity closes with the migration.
