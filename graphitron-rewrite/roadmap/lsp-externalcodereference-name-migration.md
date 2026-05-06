---
id: R93
title: "LSP quick-fix: ExternalCodeReference name → className migration"
status: Backlog
bucket: Backlog
priority: 18
theme: legacy-migration
depends-on: []
---

# LSP quick-fix: ExternalCodeReference name → className migration

The `ExternalCodeReference` input carries a deprecated `name:` field
(`@deprecated(reason: "Fases ut til fordel for class")` in
`directives.graphqls`) that resolves to a fully-qualified class name via
the plugin's `namedReferences` config map. Schemas still using the
legacy form get a runtime `WARN` per field at parse time
(`FieldBuilder.parseExternalRef`, ca. line 3313) but no editor-side
nudge or one-click migration. This item adds an LSP code action that
detects the legacy shape in the SDL and rewrites it in place.

## Detection surface

Any `ExternalCodeReference` literal in the SDL where `name:` is set and
`className:` is not. The detector keys on the input-type binding, not
on a specific directive name, so it covers every site the parser binds
to `ExternalCodeReference`:

- `@externalField(reference: …)`
- `@enum(enumReference: …)`
- `@service(service: …)`
- `@tableMethod(tableMethodReference: …)`
- `@record(record: …)`
- `@batchKeyLifter(lifter: …)`
- `@condition(condition: …)`
- `ReferenceElement.condition` (path-step conditions inside
  `@reference(path: [...])`)

Any future directive that takes `ExternalCodeReference!` picks up the
quick-fix automatically with no per-directive plumbing.

## Quick-fix shape

When the `name:` value resolves in the consumer's `namedReferences`
config (already plumbed through `RewriteContext.namedReferences()` and
read by the LSP), the action rewrites
`name: "<X>"` to `className: "<resolved-FQN>"`, preserving any sibling
`method:` and `argMapping:` slots. When the value does not resolve, a
diagnostic surfaces the unknown-name string but no auto-fix is offered
(the LSP cannot invent the FQN; the user must either add the entry to
`namedReferences` config or write `className:` explicitly).

## Open design questions

- **Diagnostic severity.** WARN (matches the parse-time behaviour) or
  INFO (the legacy form still works)? WARN aligns with the SDL
  `@deprecated()` marker; INFO avoids drowning consumers with many
  legacy sites in a noisy diagnostics panel during the migration
  window. Pick at Spec.

- **Bulk action.** Offer a workspace-level "migrate all
  `ExternalCodeReference.name` in this schema" action alongside the
  per-site quick-fix? Cheap if the per-site action is text-edit-shaped
  (compose N edits into one `WorkspaceEdit`); valuable for consumers
  with the ~49 known Sikt sites, where clicking each one is friction.

- **Interaction with R54.** R54 plans renaming `@externalField` to a
  successor name and lists the LSP quick-fix as one migration-tooling
  candidate (option b in its open questions). That migration is
  directive-name → directive-name; this one is
  input-field-name → input-field-name. The two actions live on
  different SDL surfaces (the directive's `@<name>` token vs. the
  `ExternalCodeReference` literal's `name:` field) and ship
  independently. R54's Spec may choose to fold this item's
  `WorkspaceEdit` plumbing into a shared migration-action
  infrastructure if both ship close in time.

## Out of scope

- Concrete-FQN suggestions for unresolved `name:` values (i.e. when the
  consumer wrote a name not in `namedReferences`). That would need a
  static-method index across `.java` source roots — exactly what R90
  Phase 3 builds for `@externalField` completion. When R90 ships, this
  item's diagnostic can grow a "did you mean `<FQN>`?" hint as a
  follow-on; the base item ships without it.

- Renaming the `@externalField` directive itself (R54).

- Automating the consumer's `namedReferences` config edits when the
  user prefers to keep the legacy form working. The plugin config lives
  outside the SDL surface the LSP serves; if a future "config-aware"
  LSP feature lands, this hint can attach to it then.

## Dependencies

No hard dependency on R90. The detection walks the SDL (existing
tree-sitter argument-walking pattern); the resolution lookup uses
`RewriteContext.namedReferences()` already available to the LSP; the
quick-fix is a pure SDL text edit. R90 Phase 3's static-method index
is an *enhancer* for the unresolved-name diagnostic, not a prerequisite
for the base quick-fix.
