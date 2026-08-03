---
id: R570
title: "Remove ExternalCodeReference.name and the namedReferences Mojo parameter"
status: Backlog
bucket: cleanup
priority: 3
theme: legacy-migration
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# Remove `ExternalCodeReference.name` and the `namedReferences` Mojo parameter

`ExternalCodeReference.name` is documented and warned about as a deprecated
short alias resolved through Maven configuration, but the element it was named
for no longer exists. The legacy generator's `<externalReferences>` is read
nowhere in the reactor; the rewrite renamed the binding to `<namedReferences>`
(`AbstractRewriteMojo:78`, collapsed by `toNamedReferenceMap:768` into
`RewriteContext.namedReferences()`). A consumer migrating off the legacy
generator therefore has to rename the POM block for the alias to resolve at all,
and the migration recipe never says so: `migrating-from-legacy.adoc:185` and
`:245` tell the reader to copy the FQCN out of the legacy `externalReferences:`
entry and drop it, with no mention that the rewrite has its own element that
keeps the alias alive. The alias works only for someone who discovered the new
name unaided.

The four binding sites then disagree about what happens when a name does not
resolve, and only one of them is defensible:

- `FieldBuilder.parseExternalRef:7370` (`@service`, `@externalField`) logs the
  deprecation warning, resolves through the map, and on a miss returns an
  `ExternalRef` carrying `lookupError`, which becomes a structural rejection in
  `ExternalFieldDirectiveResolver:68` and `ServiceDirectiveResolver:127`. Warning
  and error are separate signals and both fire.
- `BuildContext.readConditionDirective:2394` (field-level, argument-level, and
  input-field-level `@condition`) resolves through the map with no deprecation
  warning at all, and on a miss falls through to `return null`, which
  `ConditionResolver.resolveArg:66` and `resolveField:98` read as "directive
  absent". The predicate is dropped from the generated SQL with no warning, no
  error, and exit code 0.
- `BuildContext.resolveConditionRef:2318` (path-step `@condition`) also skips the
  warning; a miss becomes `ConditionResolution.Unresolved` and the caller reports
  `condition method '<name>' could not be resolved` (`:1929`, `:1959`, `:1997`),
  blaming a missing method for what is really an unconfigured alias.
- `@record` ignores its binding outright and `@enum` never reads `name:`, so
  neither is affected.

Rather than repair the three inconsistent arms and keep an indirection that adds
no capability (`mojo-configuration.adoc:183` says as much in the published
reference), remove the alias. A `name:` on an `ExternalCodeReference` becomes a
build rejection naming `className:`, `<namedReferences>` and its POM binding come
out, and `RewriteContext` loses the map. Every remaining in-repo usage is an LSP
test fixture (`DiagnosticsTest`, `CodeActionsTest`, `SdlActionTest`); no
production schema, sakila fixture, or plugin IT carries one, so the sweep is
small.

## Why this is not R519 repeated

R566 is currently reopening a deprecation window that R519 closed in one step,
and its stated lesson is that a warning has to ship in a release consumers
actually built against before the rejection lands. That lesson does not transfer
here, and the reason is worth stating in the Spec so a reviewer does not read
this item as the same mistake: there is no working warn-only state to preserve.
The configuration surface the alias resolves through was already renamed, so a
consumer who carries `name:` and upgrades is already broken today, loudly at
`@service` and `@externalField`, silently at `@condition`. This change makes the
build state what is already true rather than shortening a live grace period.

## Design forks

**Where the rejection lives.** Deleting `name:` from `input
ExternalCodeReference` (`directives.graphqls:397`) makes graphql-java reject the
site at schema-parse time as an unknown input field. That is an error, but a
generic one that never names `className:`. Recommend keeping the input field
declared with its `@deprecated` marker removed and rejecting in the classifier
with a migration message, mirroring the shape R519 used for `@table` on inputs.
Two consequences to carry: removing the SDL marker forces the `deprecations.adoc`
row out (`DeprecationsDocCoverageTest` is bidirectional), and the field's current
description is stale twice over, written in Norwegian and citing
`externalReferences` as the config key, so it gets rewritten as a removal notice
rather than left alone.

**The LSP quick fix.** `SdlActions.externalCodeReferenceNameToClassName` is the
only registered `SdlAction`, and `rewriteNameToClassName:141` resolves through
`catalog.namedReferences()`, which this item deletes. Either retire the action,
leaving `SdlActions.all()` returning an empty list and `SdlActionDriftTest:39`
targeting a coordinate that no longer carries a deprecation, or re-source it from
the classpath scan already on the catalog (`CompletionData.externalReferences`),
matching a simple name to an FQCN so the fix still migrates a legacy site.
Recommend re-sourcing: the action is most valuable precisely when the map is
gone, and one live action keeps the registry and its drift test meaningful.

## What has to move

**graphitron.** `FieldBuilder.parseExternalRef` (drop the warn-and-resolve arm;
a present `name:` rejects), `BuildContext.readConditionDirective`,
`resolveConditionRef`, and `extractConditionQualifiedName:2350` (which reads
`ARG_NAME` only to phrase its message). `RewriteContext` loses the
`namedReferences` component at `:62` plus the eleven convenience constructors
that thread it (`:107` through `:226`). `ARG_NAME` itself stays: it is also
`@field(name:)` and `@table(name:)`.

Note that the `no-deprecated-directive-usage` lint rule already reports `Field
'name' of input 'ExternalCodeReference' is deprecated` off the SDL marker
(`NoDeprecatedDirectiveUsageVisitor`), so a legacy site produces two warnings
today. That report goes with the marker; check `LintEngineTest` for a case pinned
on it.

**graphitron-maven-plugin.** The `namedReferences` parameter and
`toNamedReferenceMap`; delete `NamedReferenceBinding`. Tests: `GenerateMojoTest:27`,
`RewriteContextTest:29`.

**graphitron-lsp.** `Diagnostics.validateLegacyNameLeaves:775` becomes a
catalog-free "removed, use `className:`" error (it is already Error severity, so
the editor tier is currently stricter than the build tier at the `@condition`
sites). `SdlActions` per the fork above. `CompletionData.namedReferences` and its
population at `CatalogBuilder:956`. Fixtures in `DiagnosticsTest` (~`:770-880`,
`:1387`), `CodeActionsTest`, `SdlActionTest`.

**docs.** The `deprecations.adoc:32` row (forced), `mojo-configuration.adoc` at
`:5` (the preamble naming it one of two complex POM bindings), `:61` (the
parameter row), and `:183` (the `<namedReference>` binding section);
`reference/index.adoc:13`; `how-to/external-code.adoc:121`;
`how-to/connections.adoc:52`, which enumerates the Mojo's five parameters as
evidence there is no server-side page-size cap, so the count changes; and
`how-to/migrating-from-legacy.adoc:185`, `:245`, `:249`, rewritten as a hard
removal. `:249`'s claim that steps 4 through 6 "produce deprecation notices but
no failures" is already false for step 5.

Do not touch `roadmap-tool/src/main/resources/legacy-directives.graphqls`. It is
a snapshot of the legacy generator's directive surface for the support report,
not a live schema.

## Acceptance

An `ExternalCodeReference` carrying `name:` fails the build at every binding
site, with a message naming `className:`, including the field-level,
argument-level, input-field-level, and path-step `@condition` sites that today
drop the predicate or misattribute the failure. No `<namedReferences>` element is
read anywhere and the POM binding class is gone; `grep -rn namedReferences
--include=*.java --include=*.adoc` outside `target/` returns nothing. The
deprecations index no longer lists the input field. Full reactor green under
`-Plocal-db`.

## Retired vocabulary

- `namedReferences`, in all four habitats: the Mojo `@Parameter`, the
  `RewriteContext` component, the `CompletionData` component, and the
  `<namedReference>` POM element.
- `NamedReferenceBinding` and `AbstractRewriteMojo.toNamedReferenceMap`.
- `ExternalCodeReference.name` as a live alias, and the phrasing "a deprecated
  short alias resolved via the Mojo's `<namedReferences>` config" wherever the
  manual carries it.
- `Diagnostics.validateLegacyNameLeaves`, plus
  `SdlActions.externalCodeReferenceNameToClassName` and
  `rewriteNameToClassName` if the fork retires the action rather than re-sourcing
  it.
- The legacy `<externalReferences>` block as something a migrating consumer
  copies out of rather than deletes outright.
