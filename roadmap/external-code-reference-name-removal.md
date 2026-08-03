---
id: R570
title: "Remove ExternalCodeReference.name and the namedReferences Mojo parameter"
status: In Review
bucket: cleanup
priority: 3
theme: legacy-migration
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# Remove `ExternalCodeReference.name` and the `namedReferences` Mojo parameter

`ExternalCodeReference.name` is a deprecated short alias resolved through Maven
configuration, but the element it was named for no longer exists. The legacy
generator's `<externalReferences>` is read nowhere; the rewrite renamed the
binding to `<namedReferences>` (`AbstractRewriteMojo:78`, collapsed by
`toNamedReferenceMap` into `RewriteContext.namedReferences()`), and the migration
recipe never tells a consumer to rename the POM block. The alias only ever worked
for someone who found the new element name unaided, so the rename was already the
breaking change; the deprecation warning has been describing a mechanism that is
effectively gone.

The three readers also disagree about the unresolved case, which is how the gap
surfaced. `FieldBuilder.parseExternalRef` warns and rejects on a miss (via
`ExternalRef.lookupError`, consumed by `ExternalFieldDirectiveResolver` and
`ServiceDirectiveResolver`). `BuildContext.resolveConditionRef` reports
`condition method '<name>' could not be resolved`, blaming a missing method for an
unconfigured alias. `BuildContext.readConditionDirective` returns `null` on a
miss, which `ConditionResolver` reads as "directive absent", so a field-level,
argument-level, or input-field-level `@condition` silently drops its predicate
from the generated SQL with no warning and no error.

Remove the argument rather than repair the readers. `name` comes out of `input
ExternalCodeReference` in `directives.graphqls`, which makes the site a
schema-validation failure at load; the directive reference documentation states
the argument is no longer supported and points at `className:`. Every reader,
`<namedReferences>` and its POM binding, and the `RewriteContext` /
`CompletionData` components go with it. No transitional shim and no bespoke
classifier rejection: the one remaining consumer is the author of this item, and
graphql-java's unknown-field error is a sufficient signal.

Every in-repo usage is an LSP test fixture. No production schema, sakila fixture,
or plugin IT carries one.

## Scope

- `directives.graphqls`: drop `name` from `input ExternalCodeReference`. The
  `className` description's claim that a short name suffices when the package is
  imported in `externalReferences` goes too; it describes the same dead
  mechanism.
- `graphitron`: the reader arms in `FieldBuilder.parseExternalRef`,
  `BuildContext.readConditionDirective`, `resolveConditionRef`, and
  `extractConditionQualifiedName`; `ExternalRef.lookupError` and the two resolver
  arms consuming it, which have no other producer; the `RewriteContext`
  component and every convenience constructor threading it;
  `CompletionData.namedReferences` and its population in `CatalogBuilder`.
  `ARG_NAME` stays: it is also `@field(name:)` and `@table(name:)`.
- `graphitron-maven-plugin`: the `namedReferences` parameter,
  `toNamedReferenceMap`, and `NamedReferenceBinding`.
- `graphitron-lsp`: `Diagnostics.validateLegacyNameLeaves` and the
  `SdlActions.externalCodeReferenceNameToClassName` migration. Keep the generic
  `SdlAction` framework and its drift test; the registry goes empty and the next
  deprecation migration extends it. `CodeActionsTest` covers the bulk-migration
  machinery through the retired action, so it retargets onto a test-local action
  rather than losing the coverage.
- docs: the `deprecations.adoc` row (forced by `DeprecationsDocCoverageTest`),
  the `<namedReferences>` parameter and binding sections in
  `mojo-configuration.adoc`, `reference/index.adoc`,
  `how-to/external-code.adoc`, the parameter enumeration in
  `how-to/connections.adoc`, and the `name:`-to-`className:` steps in
  `how-to/migrating-from-legacy.adoc`, which become a hard removal.

`roadmap-tool/src/main/resources/legacy-directives.graphqls` is out of scope: it
is a snapshot of the legacy generator's surface for the support report, not a
live schema.

## Acceptance

A schema carrying `name:` on an `ExternalCodeReference` fails to load. No
`<namedReferences>` element is read and the POM binding is gone;
`grep -rn namedReferences` outside `target/` returns nothing. The directive
reference documentation states the argument is unsupported and names
`className:`. Full reactor green under `-Plocal-db`.

## Retired vocabulary

- `namedReferences`, in all four habitats: the Mojo `@Parameter`, the
  `RewriteContext` component, the `CompletionData` component, and the
  `<namedReference>` POM element.
- `NamedReferenceBinding` and `AbstractRewriteMojo.toNamedReferenceMap`.
- `ExternalCodeReference.name`, and the phrasing "a deprecated short alias
  resolved via the Mojo's `<namedReferences>` config" wherever the manual carries
  it.
- `ExternalRef.lookupError`, `Diagnostics.validateLegacyNameLeaves`,
  `SdlActions.externalCodeReferenceNameToClassName`, and
  `SdlActions.rewriteNameToClassName`.
- The legacy `<externalReferences>` block as something a migrating consumer
  copies out of rather than deletes outright.
