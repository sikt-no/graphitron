---
id: R93
title: "LSP quick-fix: ExternalCodeReference name → className migration"
status: In Progress
bucket: Backlog
priority: 5
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
`className:` is not. R93's detection consumes a *derived view* on a
new directive-vocabulary registry, `DirectiveDefinitions`, rather than
keying on the input type directly.

The registry's primary key is the directive name. Each entry carries
the directive's argument list with each argument's input type:

```java
public record DirectiveDef(String name, List<ArgDef> args) {}
public record ArgDef(String name, String inputType, boolean nestedPath) {}
```

The eight sites where `ExternalCodeReference` is bound become a derived
view: `DirectiveDefinitions.argsByInputType("ExternalCodeReference")`
returns the eight `(directive, argName, nestedPath?)` tuples R93's
detection iterates. Listed for orientation:

- `@externalField(reference: …)`
- `@enum(enumReference: …)`
- `@service(service: …)`
- `@tableMethod(tableMethodReference: …)`
- `@record(record: …)`
- `@batchKeyLifter(lifter: …)`
- `@condition(condition: …)`
- `@reference(path: [{ condition: … }])` (nested `ReferenceElement.condition`)

Why directive-keyed and not input-type-keyed: the input-type-keyed
framing is convenient for *this* migration but misshapen for the
long-term LSP. Future features (per-arg hover, full completion of every
arg, per-arg diagnostics) all key on directive name; an input-keyed
registry would force every consumer to invert it. `DirectiveDefinitions`
becomes the LSP's directive vocabulary catalog, with R93's migration
view being the first of several derived views.

Today three directives (`@service`, `@condition`, `@record`) are
hardcoded as parallel lookup maps in `ClassNameCompletions.outerArgOf`
(which `MethodCompletions` and the `Diagnostics` call sites delegate
through). R93 introduces `DirectiveDefinitions` and migrates that
existing lookup onto a derived view on it. The five sites that appear
in `directives.graphqls` but are unwired in the LSP today
(`@externalField`, `@enum`, `@tableMethod`, `@batchKeyLifter`,
`ReferenceElement.condition`) gain their completion / diagnostic
surface as a side effect of being added to the registry.

Adding a new `ExternalCodeReference`-binding directive later is then
a registry-entry add; the migration's derived view picks it up
automatically, and so do the existing completion / diagnostic
surfaces.

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

The migration surfaces as three activation points; the user picks
which scope to apply per invocation:

- **Per-site quick-fix.** Cursor on (or selection touching) a single
  legacy `ExternalCodeReference` literal, code-action invocation
  rewrites that one site.
- **File-scoped bulk action.** "Migrate `name:` in this file"
  composes every resolvable site in the current document into one
  `WorkspaceEdit`.
- **Workspace-scoped bulk action.** "Migrate `name:` in this
  workspace" composes every resolvable site across every `.graphqls`
  file in the `Workspace` into one multi-document `WorkspaceEdit`.

For both bulk actions, sites whose `name:` value does not resolve in
`namedReferences` are skipped; their per-site error diagnostics
(Diagnostic shape) carry the unresolved-name identity, so the user
navigates the editor's problems panel to reach each one.

The result message follows three branches keyed on the rewritten
count `N` and the unresolvable count `M`:

- `N>0, M=0`: `"Migrated N legacy ExternalCodeReference.name sites."`
- `N>0, M>0`: `"Migrated N legacy ExternalCodeReference.name sites; M unresolvable, see problems panel."`
- `N=0, M>0`: `"No resolvable legacy sites; M unresolvable, see problems panel."`

The `N=0, M=0` case never fires: the bulk action is offered only
when at least one site is detected.

The migration's per-site quick-fix surfaces independently of any
sibling diagnostic on the same range. A literal that carries, for
instance, an unrelated malformed-`argMapping:` diagnostic still
shows the migration quick-fix; the rewrite is mechanically safe
(it preserves the `argMapping:` slot verbatim), and the sibling
diagnostic stays put for its own quick-fix path.

## SdlAction primitive

Both surfaces (per-site, bulk) share the same underlying shape:
detector returns the matched literals; per-match rewrite produces a
text edit or signals skip. R93 introduces the abstraction once as
`SdlAction` — sized for any LSP-side SDL refactor, not just
deprecation migrations — with named slots and a sealed result so the
bulk action's count-by-reason pivot is typed:

```java
public record SdlAction(
    String displayName,                     // code-action title shown in the editor
    Set<DeprecationTarget> targets,         // deprecation sites the action migrates
    Detector detector,
    Rewrite rewrite
) {
    public sealed interface DeprecationTarget permits Member, WholeDirective {
        record Member(String parent, String memberName) implements DeprecationTarget {}
        record WholeDirective(String directive) implements DeprecationTarget {}
    }

    @FunctionalInterface
    public interface Detector {
        Stream<Node> detect(WorkspaceFile file);
    }

    @FunctionalInterface
    public interface Rewrite {
        RewriteResult rewrite(WorkspaceFile file, Node match);
    }

    public sealed interface RewriteResult permits Edit, Skip {
        record Edit(TextEdit edit) implements RewriteResult {}
        record Skip(String reason) implements RewriteResult {}
    }
}
```

Why these shapes:

- **`WorkspaceFile` not `(Node, byte[])`.** The pairing of a tree-sitter
  `Node` with the source bytes that produced it is a contract; passing
  a `Node` from one file with bytes from another silently produces
  wrong byte offsets. `WorkspaceFile` already names that pairing
  across `Diagnostics`, `Definitions`, `Hovers`, `Completions`.
- **Named functional interfaces.** Per-slot domain role — the
  `Detector` and `Rewrite` slots have distinct contracts (in-source
  order, finite stream, eager consumption for the first; per-match
  rewriting for the second). Named interfaces give javadoc a place
  to live.
- **Sealed `RewriteResult`.** The bulk action partitions matched sites
  into rewritten-count vs. skip-reason for the result message; with
  a nullable `TextEdit` return, the partition would be `null`-driven
  and lose typing on the skip reason.

R93 instantiates `SdlAction` once for the `name → className`
migration with `targets = { Member("ExternalCodeReference", "name") }`.
Future LSP-side SDL refactors instantiate it differently:
member-deprecation migrations (any future input-field or argument
rename) carry `Member` targets; whole-directive renames (R54's
`@externalField` rename if it picks the LSP-quick-fix option)
carry a `WholeDirective` target. Per-site quick-fix and both bulk
surfaces consume `SdlAction` instances directly, so a new refactor
ships all three activation points from one instantiation regardless
of target shape.

## Drift protection

The `SdlAction` registry and the deprecation markers in
`directives.graphqls` must agree, both directions, modulo an
allow-list for deprecations whose migration is intentionally manual.
Two marker shapes count as deprecation:

- **Member-level: SDL `@deprecated()` directive on an argument or
  input field.** The standard form, parsed straight from the SDL.
  Today: `ExternalCodeReference.name` and
  `@asConnection(connectionName:)`.
- **Whole-directive: javadoc-style `@deprecated <reason>` token in
  the directive's SDL description string.** SDL's `@deprecated`
  directive cannot apply to a directive definition (the GraphQL
  spec disallows it), so whole-directive deprecation needs a
  parallel marker that lives in source close to the deprecation
  target. The convention is a description-string token following
  javadoc's `@deprecated` block tag, free-form reason after the
  token. Today: `@index` (description currently carries the prose
  "Deprecated: use `@order(index:)` instead"; this item rewrites it
  into the structured form).

The drift walker reads both. The invariants:

- **Every `SdlAction.targets()` entry must point at an existing
  deprecation marker in `directives.graphqls`.** A stale
  `SdlAction` (target whose deprecation was removed, or whose
  directive / argument / input was renamed or deleted) breaks the
  build. `Member` targets resolve against SDL `@deprecated()`
  markers; `WholeDirective` targets resolve against the
  description-string `@deprecated` token.
- **Every deprecation marker in `directives.graphqls` must be
  covered by either an `SdlAction` or the
  `MANUAL_MIGRATION_DEPRECATIONS` allow-list.** An orphan
  deprecation (we said it's deprecated but offer no migration
  tooling and no documented "manual" reason) breaks the build.

The allow-list lives next to the test as a
`Set<SdlAction.DeprecationTarget>` constant carrying a one-line
"why" comment per entry. At R93 landing time it covers two entries:

- `Member("@asConnection", "connectionName")`: per-field semantics
  differ across instances (the override exists as a transition
  mechanism for legacy schemas); no mechanical rewrite is correct.
- `WholeDirective("index")`: the directive itself is deprecated,
  superseded by `@order(index:)`, but the migration shape is a
  per-call-site rewrite from `@index(name: "X")` on an enum value
  to `@order(index: "X")`; tractable as a future `SdlAction` but
  not in R93's scope. Allow-listed for now with a pointer to the
  follow-on.

The structured marker is also the intended landing point for the
`@externalField` directive itself during R54's window if R54
chooses not to ship an `SdlAction` — it would be added to the
allow-list as `WholeDirective("externalField")` until the rename's
parallel-support window closes.

This mirrors the existing `DeprecationsDocCoverageTest` pattern (with
its `WHOLE_DIRECTIVE_DEPRECATIONS` allow-list for spec-disallowed
whole-directive deprecations) one layer down: the docs test covers
documentation drift between SDL and `reference/deprecations.adoc`;
the new LSP test covers tooling drift between SDL and the
`SdlAction` registry. Both are bidirectional drift seams against the
same `directives.graphqls` source of truth. The docs test currently
hand-maintains its `WHOLE_DIRECTIVE_DEPRECATIONS` allow-list as a
literal `Set.of("index")`; once R93's structured marker lands, that
test could derive the same set from the SDL parse instead of holding
a parallel constant. That migration is out of scope for R93 (the
docs test stays put) but is a small, follow-on simplification once
the marker convention exists.

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

- `parsing/DirectiveDefinitions.java` — the directive-vocabulary
  registry. Keyed on directive name; each entry carries the
  directive's argument list with each argument's input type plus the
  nested-path flag for `ReferenceElement.condition`-style sites.
  Exposes the derived view `argsByInputType(String)` consumed by
  R93's migration plus by the existing `outerArgOf` callers after
  migration.
- `code_action/SdlAction.java` — the reusable primitive
  (`Detector`, `Rewrite`, sealed `RewriteResult`).
- `code_action/CodeActions.java` — entry-point provider. Handles
  `textDocument/codeAction` requests, runs the `name → className`
  migration's `SdlAction` instance, and emits per-site quick-fix +
  file-scoped bulk + workspace-scoped bulk code actions. Bulk actions
  emit multi-document `WorkspaceEdit`s directly (no
  `executeCommand` indirection unless impl finds a need).
- `code_action/SdlActions.java` — registry of every `SdlAction`
  instance plus the `MANUAL_MIGRATION_DEPRECATIONS` allow-list for
  deprecations whose migration is intentionally manual.
- `parsing/DeprecationMarkers.java` — walks
  `directives.graphqls` once and emits the canonical set of
  deprecation targets: member-level via SDL `@deprecated()` on
  arguments and input-type fields, whole-directive via the
  javadoc-style `@deprecated <reason>` token in directive
  description strings. Returns `Set<SdlAction.DeprecationTarget>`
  for the drift test to compare against the registry. Same parser
  is consumed by impl-tier code-action labels if needed (so the
  reason string in the marker becomes hover-readable as a free
  side-effect, but R93 doesn't surface it as a feature).

Modified files outside the LSP module:

- `graphitron-rewrite/graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls` —
  `@index`'s description string converts the existing prose
  "Deprecated: use `@order(index:)` instead" into the structured
  javadoc-style form "@deprecated use `@order(index:)` instead"
  so `DeprecationMarkers` can pick it up. No semantic change for
  consumers; the prose deprecation hint stays human-readable.

Modified files in the same module:

- `completions/ClassNameCompletions.java` — `outerArgOf` replaced by
  a call into `DirectiveDefinitions.argsByInputType("ExternalCodeReference")`.
  The five previously unwired sites (`@externalField`, `@enum`,
  `@tableMethod`, `@batchKeyLifter`, `ReferenceElement.condition`)
  start returning candidates as a side effect.
- `completions/MethodCompletions.java` — already delegates to
  `ClassNameCompletions.outerArgOf`; no edit beyond the upstream
  change. New sites gain method-name completion as the same side
  effect.
- `diagnostics/Diagnostics.java` — `compute()` (around line 41–49)
  enumerates `(directive, outerArg)` tuples explicitly today; switches
  to iterating the same `DirectiveDefinitions.argsByInputType` view.
  New sites gain the existing `validateExternalCodeReference` arms
  (`lookupError`, argMapping, etc.) automatically. Adds the
  legacy-and-unresolved arm described in "Diagnostic shape" —
  error-severity diagnostic mirroring `FieldBuilder.parseExternalRef`'s
  `ExternalRef.lookupError` (around line 3318).
- `server/GraphitronTextDocumentService.java` — wires `codeAction(...)`
  to `CodeActions`, advertises `codeActionProvider` in
  `serverCapabilities()`.
- `state/Workspace.java` — exposes the `namedReferences` lookup the
  code-action provider consumes (the `RewriteContext` it carries
  already holds the map; the surface may need a thin getter).

## Tests

Four tests, organised by tier:

### Unit-tier

- `DirectiveDefinitionsTest`: assert the registry contains every
  directive in `directives.graphqls` with the right argument list
  per directive; assert `argsByInputType("ExternalCodeReference")`
  returns the eight `(directive, argName, nestedPath?)` tuples;
  assert iterate-all and lookup-by-directive surfaces both work.
- `SdlActionTest`: synthetic SDL fixture with mixed legacy / modern
  shapes; the detector returns the expected literal ranges; the
  rewrite slot returns `RewriteResult.Edit` for resolvable sites
  with the expected `TextEdit`, and `RewriteResult.Skip` carrying
  the unresolved name for unresolvable sites.
- `SdlActionDriftTest`: walks the canonical deprecation-target
  set from `DeprecationMarkers` (covering both SDL `@deprecated()`
  on arguments / input-type fields and the description-string
  `@deprecated <reason>` token on directive definitions) plus the
  `SdlActions` registry; asserts both directions of the
  drift-protection invariant. Stale-target test: every
  `SdlAction.targets()` entry (`Member` and `WholeDirective` arms
  both) corresponds to an existing marker. Orphan-deprecation
  test: every marker is covered by either an `SdlAction` or the
  `MANUAL_MIGRATION_DEPRECATIONS` allow-list. Per-arm fixture
  cases assert the parser handles both marker shapes; the test
  also asserts the at-landing-time set is exactly
  `{ Member("ExternalCodeReference", "name"),
  Member("@asConnection", "connectionName"),
  WholeDirective("index") }`. Sibling pattern to
  `DeprecationsDocCoverageTest`'s SDL-to-docs invariant.
- `DeprecationMarkersTest`: parser unit test against synthetic
  SDL fixtures. Covers: SDL `@deprecated()` on directive args
  (none today but possible); SDL `@deprecated()` on input-type
  fields; description-string `@deprecated <reason>` token in
  various positions (start of description, mid-description,
  multi-line description); a description string carrying the
  prose word "deprecated" but not the structured token (must not
  match); a directive whose description is missing entirely.

### LSP-tier

- `CodeActionsTest`: `textDocument/codeAction` requests against
  fixture documents return the expected `WorkspaceEdit`s. Cases:
  per-site on a resolvable literal (one `TextEdit`); per-site on
  an unresolvable literal (no quick-fix offered; diagnostic
  present); per-site on a literal carrying an unrelated sibling
  diagnostic (quick-fix surfaces regardless); file-scoped bulk
  action (one `WorkspaceEdit` covering every resolvable site in
  the document, unresolvable sites skipped); workspace-scoped bulk
  action (one multi-document `WorkspaceEdit` across multiple
  fixture files); each bulk-action result message matches the
  three-branch wording in "Quick-fix shape".
- `DiagnosticsTest` extension: legacy-and-resolves emits no
  diagnostic; legacy-and-unresolved emits one error-severity
  diagnostic naming the unresolved name and pointing at the two
  fixes. Coverage extends to all eight binding sites — the test
  fixture grows to include one example per site.
- `ClassNameCompletionsTest` extension: existing three-site cases
  pass after the `outerArgOf` migration onto
  `DirectiveDefinitions.argsByInputType` (no behaviour change for
  `@service` / `@condition` / `@record`); five new cases cover
  the previously unwired sites.

## Phasing

Two slices, each independently shippable through the canonical
Backlog → Spec → Ready → In Progress → In Review → Done flow.

### Phase 1: directive-vocabulary registry + light up unwired sites

- Introduce `DirectiveDefinitions` keyed on directive name, with
  `argsByInputType(String)` as the derived view.
- `ClassNameCompletions.outerArgOf` migrates onto
  `DirectiveDefinitions.argsByInputType("ExternalCodeReference")`.
- `Diagnostics.compute()` iterates the same view rather than its
  hardcoded tuple list.
- All five previously unwired sites gain completion + diagnostic
  surface as a side effect.
- `DirectiveDefinitionsTest` plus the `ClassNameCompletionsTest` /
  `DiagnosticsTest` extensions.

Acceptance: existing three-site coverage extends to all eight; no
behaviour change for the existing three; the registry is positioned
to host future per-arg / per-directive features beyond R93.

### Phase 2: code-action surface

- Introduce `SdlAction` (the primitive, with the sealed
  `DeprecationTarget { Member | WholeDirective }` shape) and
  instantiate it for the `name → className` migration with
  `targets = { Member("ExternalCodeReference", "name") }`.
- `SdlActions` registry plus the `MANUAL_MIGRATION_DEPRECATIONS`
  allow-list (covering `Member("@asConnection", "connectionName")`
  and `WholeDirective("index")` at landing time).
- `DeprecationMarkers` parser plus the structured-form rewrite of
  `@index`'s description in `directives.graphqls`.
- `CodeActions` provider, registered in
  `GraphitronTextDocumentService`. Three activation points: per-site,
  file-scoped bulk, workspace-scoped bulk.
- The legacy-and-unresolved diagnostic arm in `Diagnostics`.
- `SdlActionTest`, `CodeActionsTest`, `DeprecationMarkersTest`, and
  `SdlActionDriftTest`.

Landing-commit coupling: the `SdlActionDriftTest` invariants assert
total coverage in both directions, so the test, the `SdlAction`
instantiation for `name → className`, the `MANUAL_MIGRATION_DEPRECATIONS`
allow-list entries (`Member("@asConnection", "connectionName")` and
`WholeDirective("index")`), and `@index`'s structured-form
description rewrite all land in the same commit. Splitting any of
these across commits leaves the test red on intermediate revisions.

Acceptance: editor users can apply the migration on a legacy `name:`
literal; "Migrate `name:` in this file" and "Migrate `name:` in this
workspace" each compose the resolvable sites into one `WorkspaceEdit`
at the named scope. Unresolvable sites surface as error diagnostics
in the problems panel and skip the rewrite. The `SdlActionDriftTest`
invariants pass: every action targets an existing marker (member or
whole-directive), every marker is covered (action or allow-list).
The at-landing-time canonical set is exactly
`{ Member("ExternalCodeReference", "name"),
Member("@asConnection", "connectionName"),
WholeDirective("index") }`.

## Open architectural decisions

The major shape decisions are pinned in the body above:
`DirectiveDefinitions` keyed on directive name; `SdlAction` with
named `Detector`/`Rewrite` interfaces and sealed `RewriteResult`;
three-branch result wording locked in; bulk action emits
multi-document `WorkspaceEdit`s directly; per-site quick-fix surfaces
independently of sibling diagnostics. The remaining unknowns are
scope-only:

1. **Sibling hardcoded directive lookups elsewhere in the LSP.**
   Phase 1 migrates `ClassNameCompletions.outerArgOf` (the canonical
   lookup; `MethodCompletions` and the relevant `Diagnostics` call
   sites delegate through it). If implementation surfaces another
   hardcoded directive→arg map in `Hovers`, `Definitions`, or
   elsewhere, it migrates onto `DirectiveDefinitions` as part of
   Phase 1 silently rather than spawning a separate amendment.
2. **`DirectiveDefinitions` population strategy.** Open between
   (a) hand-written entries, kept in sync with `directives.graphqls`
   by a build-tier drift test; (b) parsed from `directives.graphqls`
   at LSP startup. (a) is cheaper, (b) is the longer-term shape.
   Phase 1 picks (a) unless impl finds (b) free; either is opaque
   to the migration's derived view.

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
  same character range. The "SdlAction primitive" section above
  names what R54 inherits if it picks the LSP-quick-fix option (the
  `SdlAction` shape and the three activation points wrapping it);
  beyond that R93 has no dependency on R54's direction or vice
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
