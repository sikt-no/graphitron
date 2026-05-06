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
`className:` is not. The detector iterates an explicit
`ExternalCodeReferenceBindings` registry (working name; final shape
decided at Spec) that lists every `(directiveName, outerArgName,
nestedPath?)` pair the parser binds to `ExternalCodeReference`:

- `@externalField(reference: …)`
- `@enum(enumReference: …)`
- `@service(service: …)`
- `@tableMethod(tableMethodReference: …)`
- `@record(record: …)`
- `@batchKeyLifter(lifter: …)`
- `@condition(condition: …)`
- `@reference(path: [{ condition: … }])` (nested `ReferenceElement.condition`)

The registry is the single source of truth for "where can an
`ExternalCodeReference` literal appear in the SDL". Today three sites
(`@service`, `@condition`, `@record`) are hardcoded as parallel lookup
maps in `Diagnostics.outerArgOf` and `ClassNameCompletions.outerArgOf`;
R93 introduces the registry and migrates those existing sites onto it.
The remaining five sites that appear in `directives.graphqls` but are
unwired in the LSP today (`@externalField`, `@enum`, `@tableMethod`,
`@batchKeyLifter`, `ReferenceElement.condition`) gain their detection
/ completion / diagnostic surface as part of this work.

Adding a new `ExternalCodeReference`-binding directive later is then a
one-line registry add — not literally "free", but the surface where
the change lands is named.

## Quick-fix shape

When the `name:` value resolves in the consumer's `namedReferences`
config (already plumbed through `RewriteContext.namedReferences()` and
read by the LSP), the action rewrites
`name: "<X>"` to `className: "<resolved-FQN>"`, preserving any sibling
`method:` and `argMapping:` slots. When the value does not resolve, no
auto-fix is offered (the LSP cannot invent the FQN; the user must
either add the entry to `namedReferences` config or write `className:`
explicitly). The diagnostic stance for both arms lives in
"Diagnostic shape" below.

Two activation surfaces ship together:

- **Per-site quick-fix.** Cursor on (or selection touching) a single
  legacy `ExternalCodeReference` literal, code-action invocation
  rewrites that one site.
- **Workspace-level bulk action.** "Migrate all
  `ExternalCodeReference.name` in this schema" composes the N
  resolvable per-site edits into one `WorkspaceEdit` and applies them
  atomically. Sites whose `name:` value does not resolve in
  `namedReferences` are skipped; their per-site error diagnostics
  (Diagnostic shape) carry the unresolved-name identity, so the user
  navigates the editor's problems panel to reach each one. The bulk
  action's result message reports the count of rewritten sites and
  refers to those diagnostics for the rest. Targeted at consumers
  with many legacy sites (Sikt has ~49 known) where per-site clicking
  is friction.

## Reusable migration primitive

The per-site and bulk surfaces share the same shape — detector returns
the ranges, edit-builder produces the textual replacement — so R93
introduces the abstraction once: an `SdlMigration` carrier (working
name) with two slots, the detector and the per-range rewrite. R93
instantiates it for the `name → className` migration; future
SDL-rewriting migrations (directive renames, argument-name changes,
etc.) instantiate it differently. The per-site quick-fix and the
workspace-level bulk action both consume `SdlMigration` instances
directly, so adding a new migration ships both surfaces from one
instantiation. The rewrite slot is a function of the matched range,
not specific to any one directive.

R54's directive-rename is the immediate next consumer if it picks the
LSP-quick-fix migration option; the primitive is sized for that use
specifically.

## Diagnostic shape

The diagnostic stance splits on whether the legacy `name:` value
resolves in `namedReferences`:

- **Legacy and resolves.** No LSP diagnostic. The legacy form is
  runtime-correct (resolves via `RewriteContext.namedReferences()` to
  the same FQN `className:` would carry, building an identical
  `MethodRef`); there is no consumer-facing problem to surface. The
  quick-fix surfaces as an unprompted code action on the literal,
  discoverable when the cursor sits on the line. Migration-tracking
  for graphitron developers comes through the build-channel
  `LOG.warn` in `FieldBuilder.parseExternalRef` (around line 3313),
  which already exists and emits one entry per field per build.
- **Legacy and unresolved.** Error-severity diagnostic at the
  literal, mirroring `FieldBuilder.parseExternalRef`'s
  `ExternalRef.lookupError` arm (around line 3318) — this is a
  build-fail condition today, and the LSP diagnostic surfaces it
  ahead of the build. No auto-fix (the FQN is unknown to the LSP);
  the diagnostic message names the unresolved name and points at the
  two fixes (add to `namedReferences` config, or write `className:`
  directly).

The split keeps the LSP classifier-aligned: the diagnostic mirrors the
classifier's actual rejection arm (`lookupError`), not the deprecation
arm (which is correctly silent at the LSP layer because the build
channel already covers it).

## Out of scope

- Concrete-FQN suggestions for unresolved `name:` values (i.e. when the
  consumer wrote a name not in `namedReferences`). That would need a
  static-method index across `.java` source roots — exactly what R90
  Phase 3 builds for `@externalField` completion. When R90 ships, the
  legacy-and-unresolved diagnostic can grow a "did you mean `<FQN>`?"
  hint as a follow-on; the base item ships without it.

- Renaming the `@externalField` directive itself (R54). R93 and R54
  target disjoint SDL surfaces (the input-field token `name:` inside
  an `ExternalCodeReference` literal vs. the directive token
  `@externalField` itself); the two literals never co-occur at the
  same character range. The "Reusable migration primitive" section
  above names what R54 inherits if it picks the LSP-quick-fix option
  (the `SdlMigration` shape; the per-site / bulk surfaces wrapping
  it); beyond that R93 has no dependency on R54's direction or vice
  versa.

- Automating the consumer's `namedReferences` config edits when the
  user prefers to keep the legacy form working. The plugin config
  lives outside the SDL surface the LSP serves; if a future
  "config-aware" LSP feature lands, this hint can attach to it then.

## Dependencies

- **R90: none (hard).** The detection walks the SDL (existing
  tree-sitter argument-walking pattern); the resolution lookup uses
  `RewriteContext.namedReferences()` already available to the LSP;
  the quick-fix is a pure SDL text edit. R90 Phase 3's static-method
  index is an *enhancer* for the legacy-and-unresolved diagnostic
  ("did you mean `<FQN>`?"), not a prerequisite for the base item.
- **`FieldBuilder.parseExternalRef`'s `LOG.warn` (existing).**
  Load-bearing for the legacy-and-resolves diagnostic arm above:
  that arm relies on the WARN being the migration-tracking signal
  for graphitron developers. If a future change relaxes the WARN's
  severity or removes it, R93's no-diagnostic stance for
  legacy-and-resolves needs revisiting before that change ships.
