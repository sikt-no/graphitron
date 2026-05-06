---
id: R93
title: "LSP quick-fix: ExternalCodeReference name → className migration"
status: Spec
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

## Implementation sites

New files under `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/`:

- `parsing/ExternalCodeReferenceBindings.java` — registry of every
  `(directiveName, outerArgName, nestedPath?)` tuple where the parser
  binds an `ExternalCodeReference`. Eight entries at landing time
  (the seven directives plus `@reference(path: [{condition: …}])`).
  Single source of truth for "where can an `ExternalCodeReference`
  literal appear in the SDL".
- `code_action/SdlMigration.java` — the reusable primitive (working
  name; final shape per "Open architectural decisions" below). Two
  slots: detector (returns the ranges to act on) and per-range
  rewrite (produces the text edit per range).
- `code_action/CodeActions.java` — entry-point provider. Receives the
  LSP `textDocument/codeAction` request, runs the
  `name → className` migration's `SdlMigration` instance, and emits
  per-site quick-fix and workspace-level bulk-action code actions.

Modified files in the same module:

- `completions/ClassNameCompletions.java` — `outerArgOf` is replaced
  by a call into `ExternalCodeReferenceBindings`. The five
  previously unwired sites (`@externalField`, `@enum`, `@tableMethod`,
  `@batchKeyLifter`, `ReferenceElement.condition`) start returning
  candidates as a side effect.
- `completions/MethodCompletions.java` — already delegates to
  `ClassNameCompletions.outerArgOf`; no edit needed beyond the
  upstream change. New sites gain method-name completion as the
  same side effect.
- `diagnostics/Diagnostics.java` — `compute()` (around line 41–49)
  enumerates `(directive, outerArg)` tuples explicitly today; switches
  to iterating `ExternalCodeReferenceBindings`. New sites gain the
  existing `validateExternalCodeReference` arms (`lookupError`,
  argMapping, etc.) automatically. Adds the legacy-and-unresolved
  arm described in "Diagnostic shape" — error-severity diagnostic
  mirroring `FieldBuilder.parseExternalRef`'s `ExternalRef.lookupError`
  (around line 3318).
- `server/GraphitronTextDocumentService.java` — wires `codeAction(...)`
  to `CodeActions`, advertises `codeActionProvider` (and
  `executeCommandProvider` for the bulk action) in
  `serverCapabilities()`.
- `state/Workspace.java` — exposes the `namedReferences` lookup the
  code-action provider consumes (the `RewriteContext` it carries
  already holds the map; the surface may need a thin getter).

## Tests

Four tests, organised by tier:

### Unit-tier

- `ExternalCodeReferenceBindingsTest`: assert the registry contains
  all eight binding sites with the right
  `(directive, outerArg, nestedPath?)` shape; assert lookup-by-
  directive and iterate-all surfaces both work.
- `SdlMigrationTest`: synthetic SDL fixture with mixed legacy /
  modern shapes; the detector returns the expected literal ranges,
  the rewrite slot produces the expected `TextEdit` per range,
  resolvable vs. unresolvable sites partition correctly.

### LSP-tier

- `CodeActionsTest`: `textDocument/codeAction` requests against a
  fixture document return the expected `WorkspaceEdit`. Cases:
  per-site on a resolvable literal (one `TextEdit`); per-site on
  an unresolvable literal (no quick-fix offered; diagnostic
  present); workspace-level bulk action (one `WorkspaceEdit`
  covering every resolvable site, unresolvable sites skipped).
- `DiagnosticsTest` extension: legacy-and-resolves emits no
  diagnostic; legacy-and-unresolved emits one error-severity
  diagnostic naming the unresolved name and pointing at the two
  fixes. Coverage extends to all eight binding sites — the test
  fixture grows to include one example per site.
- `ClassNameCompletionsTest` extension: existing three-site cases
  pass after the `outerArgOf` migration (no behaviour change for
  `@service` / `@condition` / `@record`); five new cases cover
  the previously unwired sites.

## Phasing

Two slices, each independently shippable through the canonical
Backlog → Spec → Ready → In Progress → In Review → Done flow.

### Phase 1: registry + light up unwired sites

- Introduce `ExternalCodeReferenceBindings`.
- `ClassNameCompletions.outerArgOf` migrates onto it.
- `Diagnostics.compute()` iterates the registry rather than its
  hardcoded tuple list.
- All five previously unwired sites gain completion + diagnostic
  surface as a side effect.
- `ExternalCodeReferenceBindingsTest` plus the
  `ClassNameCompletionsTest` / `DiagnosticsTest` extensions.

Acceptance: existing three-site coverage extends to all eight; no
behaviour change for the existing three.

### Phase 2: code-action surface

- Introduce `SdlMigration` and the `name → className` instance.
- `CodeActions` provider, registered in
  `GraphitronTextDocumentService`.
- The legacy-and-unresolved diagnostic arm in `Diagnostics`.
- `SdlMigrationTest` and `CodeActionsTest`.

Acceptance: editor users can right-click on a legacy `name:` literal
and apply the migration; "Migrate all `ExternalCodeReference.name` in
this schema" composes the resolvable sites into one `WorkspaceEdit`.
Unresolvable sites surface as error diagnostics in the problems panel.

## Open architectural decisions

1. **`SdlMigration` final shape.** The spec uses the working name
   to mean "detector returning ranges + per-range rewrite producing
   text edit". Final shape (record? sealed interface? functional
   interface taking the parsed tree-sitter node?) decided during
   Phase 2 implementation; the choice does not affect Phase 1.
2. **`outerArgOf` migration scope.** Phase 1 migrates
   `ClassNameCompletions.outerArgOf` (the canonical lookup; both
   `MethodCompletions` and the relevant `Diagnostics` call sites
   already delegate through it). If implementation surfaces a
   sibling hardcoded map elsewhere in the LSP, it joins Phase 1
   silently rather than carrying a separate spec amendment.
3. **Bulk-action result wording.** The structural commitment is
   "report rewritten count, refer to per-site diagnostics for the
   rest"; the literal string ("Migrated N legacy `name:` sites; M
   unresolvable — see problems panel") is finalised at impl time.

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
