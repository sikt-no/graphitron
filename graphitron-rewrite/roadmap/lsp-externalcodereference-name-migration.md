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

Two activation surfaces ship together:

- **Per-site quick-fix.** Cursor on (or selection touching) a single
  legacy `ExternalCodeReference` literal, code-action invocation
  rewrites that one site.
- **Workspace-level bulk action.** "Migrate all
  `ExternalCodeReference.name` in this schema" composes the N
  per-site edits into one `WorkspaceEdit` and applies them atomically.
  Sites whose `name:` value does not resolve in `namedReferences` are
  skipped (their diagnostics remain) so the bulk action never silently
  loses information; the action's result message names the count of
  rewritten and skipped sites. Targeted at consumers with many legacy
  sites (Sikt has ~49 known) where per-site clicking is friction.

## Diagnostic shape

The legacy `name:` form is runtime-correct: it resolves via
`RewriteContext.namedReferences()` to the same FQN that `className:`
would carry, and the resolver builds an identical `MethodRef`. There
is no consumer-facing problem to surface, so the LSP emits no
diagnostic for legacy sites (no entry in the editor's problems
panel). The quick-fix instead surfaces as an unprompted code action
on every legacy `ExternalCodeReference` literal — discoverable when
the cursor sits on the line, not preceded by a problem report.

The existing parse-time `LOG.warn(...)` in
`FieldBuilder.parseExternalRef` (around line 3313, one entry per
field) lives on a different channel (build log, not editor) and
serves graphitron-developer migration-tracking rather than consumer
feedback. Whether to downgrade it to DEBUG to match the LSP framing
is a sibling decision, out of scope here.

## Out of scope

- Concrete-FQN suggestions for unresolved `name:` values (i.e. when the
  consumer wrote a name not in `namedReferences`). That would need a
  static-method index across `.java` source roots — exactly what R90
  Phase 3 builds for `@externalField` completion. When R90 ships, this
  item's diagnostic can grow a "did you mean `<FQN>`?" hint as a
  follow-on; the base item ships without it.

- Renaming the `@externalField` directive itself (R54). R93 and R54
  target disjoint SDL surfaces (the input-field token `name:` inside an
  `ExternalCodeReference` literal vs. the directive token `@externalField`
  itself); the two literals never co-occur at the same character range,
  and either fix is valid in isolation. The only forward-leaning effect
  is that the per-site + bulk `WorkspaceEdit` machinery R93 lands becomes
  reusable infrastructure for any future SDL-rewriting migration; if R54
  picks the LSP-quick-fix migration option, it inherits that machinery
  for free rather than repaving it.

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
