---
id: R54
title: "Rename @externalField (parallel-support, deprecation, migration)"
status: Backlog
bucket: cleanup
priority: 5
theme: service
depends-on: []
---

# Rename `@externalField` (parallel-support, deprecation, migration)

`@externalField` lifted to `IMPLEMENTED_LEAVES` end-to-end in
`computed-field-with-reference` (R48, shipped; see
[`changelog.md`](changelog.md)). The directive's name is the surviving
historical artefact: it predates the `ChildField.ComputedField` model
variant and reads as "field resolved by external code" rather than the
narrower behaviour the lift settled on (a `Field<X>` returned by a static
method, inlined into the SELECT projection at the alias). A clearer name
ships in this plan; the old name stays accepted for one consumer-migration
window.

## Open design questions

- **Successor name.** `@computed` and `@calculated` are both candidates.
  `@computed` is shorter and matches `ComputedField`; `@calculated` reads
  more clearly when the GraphQL field's value is derived from but visibly
  not stored on the row, and avoids overloading "compute" against the
  generator's own use of that word in pipeline language. Pick at Spec.

- **Parallel-support window length.** Open between (a) hard expiry tied
  to a release boundary (e.g. "removed in graphitron `11`"), (b) one full
  Sikt consumer-migration cycle observed in build logs, (c) open-ended
  until backlog priority demands it. Sikt has ~49 known `@externalField`
  call sites today (`changelog.md` R48 entry); the migration tooling
  decision below feeds back into how short the window can be.

- **Deprecation-warning channel.** The classifier today routes through
  `AUTHOR_ERROR` (build-fail) and `RejectionKind.DEFERRED` (skipped with a
  reason). Deprecation warnings need a third tier that surfaces in build
  logs without failing the build. Either: (a) reuse the existing per-site
  WARN pattern from `IdReferenceField`'s synthesis shim (`changelog.md`
  R39ish entry calls out the format `parentTypeName.fieldName` and the
  canonical replacement string, so future migration tooling can parse the
  log); (b) introduce an explicit `DeprecationWarning` carrier on the
  classified model that the validator surfaces; (c) annotate the
  classified `ComputedField` with an optional `legacyName` and let the
  validator emit a single aggregated warning. The shim WARN pattern is
  the cheapest and already proven; (b) and (c) are warranted only if a
  second deprecation case lands during the parallel window.

- **Migration tooling shape.** Three candidates ordered by cost: (a)
  document the rename in `changelog.md` and the `getting-started.adoc`
  migration section, no tooling beyond grep; (b) extend `graphitron-lsp`
  with a quick-fix code action that swaps the directive name in place
  (depends on `graphitron-lsp` being far enough along, see R-id for the
  LSP plan); (c) a one-shot `mvn graphitron-rewrite:migrate` mojo that
  rewrites `.graphqls` files in place under `<schemaInputs>`. Sikt's ~49
  sites are tractable by hand; option (a) is likely sufficient unless the
  parallel window is short enough that automated migration becomes the
  unblocker rather than the convenience.

- **Internal model collapse.** Both directives map to the same
  `ChildField.ComputedField` variant, so the classifier branches on
  directive name in `FieldBuilder` and converges to a single carrier
  immediately. Should the new directive name surface anywhere in the
  classified model (e.g. as a `legacyName` slot for the deprecation
  warning), or stay an artefact of parsing? Tied to the deprecation-channel
  decision.

## Out of scope (for the Spec follow-on, not for this Backlog placeholder)

- Renaming `ChildField.ComputedField` itself. The variant carries the
  long-term name; the SDL directive is the user-facing surface that
  changes.

- Wider directive renames (`@externalField` is the explicit target). If
  Spec discovers other directive names worth retiring (`@field`'s
  multi-axis overload is a recurring concern but predates this plan), file
  separately.

## Workflow note

Bug observation alongside this filing: the `roadmap-tool create`
subcommand allocated `R53` for this Backlog item, but R53 was just used
by `external-code-reference-arg-mapping` (shipped Done in `26c52b8`,
recorded in `changelog.md`). The tool must consult historical IDs in
`changelog.md` to honour the workflow's "IDs are *never reused*" rule
(see `workflow.adoc` line 39). Fixed manually to `R54` here; tool fix
worth filing as a separate Backlog item.
