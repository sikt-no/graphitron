---
id: R578
title: "Raise the LSP unknown-input-field diagnostic to Error severity"
status: Backlog
bucket: cleanup
priority: 3
theme: lsp
depends-on: []
created: 2026-08-03
last-updated: 2026-08-03
---

# Raise the LSP unknown-input-field diagnostic to Error severity

`Diagnostics.descendUnknownArgs` reports an input field that the SDL vocabulary does
not declare at `Warning` severity. Every such field is a hard schema-load failure at
build time (graphql-java rejects the directive application outright), so the editor
tier is strictly softer than the build tier for a condition that cannot be ignored.
An author sees a yellow squiggle, keeps typing, and the build refuses to load the
schema.

Surfaced while reviewing the `ExternalCodeReference.name` removal. That change retired
a bespoke `Error`-severity diagnostic at the `name:` coordinate and now leans on this
generic validator, which reports `Unknown field 'name' on input type
'ExternalCodeReference'.` at `Warning`. The severity drop is not specific to that
coordinate; it is the generic arm's policy, and re-adding a per-coordinate arm is
exactly the indirection that change removed. Fix it once, generically.

Scope is the severity on the `descendUnknownArgs` arm and the sibling
`validateUnknownArgs` top-level "Unknown argument" arm, which has the same property.
Check `RejectionSeverityCoverageTest` and the LSP severity conventions before flipping,
and note that neither arm currently has any test pinning its message or severity, so
the change wants a diagnostics-tier case per arm.
