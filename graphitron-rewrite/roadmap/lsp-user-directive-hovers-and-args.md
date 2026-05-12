---
id: R142
title: "LSP hovers, arg-completion, and arg validation against the schema snapshot"
status: In Progress
bucket: architecture
priority: 5
theme: lsp
depends-on: [lsp-schema-snapshot-side-channel]
---

# LSP hovers, arg-completion, and arg validation against the schema snapshot

> Phase 2 of the LSP schema-snapshot side-channel (R139 was phase 1). The snapshot now lives on `Workspace`, exposed through the sealed `DirectiveResolution` result (`Bundled | User | Unknown`); R139 wired it into the unknown-directive arm only. This item extends the same data flow to three more user-visible consumers: hover prose on user-declared directives and their args, top-level unknown-arg and required-arg diagnostics on user-declared directives, and arg-name completion for user directives. Each consumer reads through the same `Workspace.resolveDirective(name)` / static `DirectiveResolution.resolve(vocabulary, snapshot, name)` entrypoint phase 1 added; no new data flows in.

This is the natural follow-on to R139. The spec body for R139 reserved phase 2 as its own item so the snapshot's actual shape could be in-tree before phase 2 lands, and so phase 2's user-visible surface (three more arms, three more diagnostic + completion + hover surfaces) could be reviewed independently of the plumbing. Phase 1 shipped at `a9c9e54` (`R139 implementer learnings: phase 2 prep notes`); this body picks up its prep notes inline below.

---

## Motivation

Today, three LSP surfaces silently degrade on user-declared directives:

* `Hovers.compute` at `graphitron-lsp/.../hover/Hovers.java:38-61` resolves coordinates against `LspVocabulary.behaviorAt` (bundled overlay) and falls back to `vocabulary.descriptionOf(coord)` (bundled SDL docstrings). A user-declared `@auth(role: String!)` produces no hover, even when the parsed snapshot has the directive's name, args, and description.
* `Diagnostics.validateUnknownArgs` and `validateRequiredArgs` at `Diagnostics.java:137-204` run only inside the `Bundled` arm of the phase 1 switch. A user-declared `@key(fields: "id")` arg name typo (`@key(felds: "id")`) is silenced because the diagnostic only fires for known directives; the user gets no feedback until build time, where the SDL-parse failure is the only signal and it doesn't pin-point the typo's column.
* `ArgNameCompletions.generate` at `ArgNameCompletions.java:46-47` short-circuits on `dirDef.isEmpty()`. Typing `@auth(<cursor>)` gives no completions even when the snapshot has the directive's args.

R139's plumbing is sufficient: `DirectiveShape.args()` carries each arg's `InputValueShape` (name, type, description) and `DirectiveShape.description()` carries the directive's prose. The phase 2 arms read the same projection through the same resolution function, so the data flow stays narrow: no new producer, no new lifecycle, no new module boundary.

The freshness gate (R139's `Built.Current` vs `Built.Previous` distinction) does not apply uniformly. Hovers and completion *want* stale info when the parse fails (better an old hover than nothing); diagnostics want freshness only on the warn-on-typo arm (the same trade-off R139's unknown-directive arm settled). The sealed result lets each consumer make the call locally, with the compile-time exhaustive switch as the safety net.

---

## Design

### One resolution function, three consumer arms

Phase 1 introduced `DirectiveResolution.resolve(LspVocabulary, LspSchemaSnapshot, String)` at `graphitron-lsp/.../state/DirectiveResolution.java`. Every phase 2 surface reads through that function (directly or via `Workspace.resolveDirective(String)`) and switches on the sealed `Bundled | User | Unknown` result. The static entrypoint already encodes the bundled-shadows-snapshot precedence; consumers do not re-check it.

The three new arms:

```java
// Pseudocode shared by all three consumers; full diff lives in Implementation sites.
var resolution = DirectiveResolution.resolve(vocabulary, snapshot, name);
return switch (resolution) {
    case DirectiveResolution.Bundled b -> existingBundledPath(b.def());        // unchanged
    case DirectiveResolution.User u    -> snapshotPath(u.shape());              // new in phase 2
    case DirectiveResolution.Unknown ignored -> noOpOrCurrentFallback;
};
```

The `User` arm always falls through both `Built.Current` and `Built.Previous`: hovers, completions, and arg validation all prefer stale info over silence. The single exception is the typo-warning case the user is most likely to want freshness on (and even that is policy-relaxed in R139's design, see Settled design note 4 in `lsp-schema-snapshot-side-channel.md`). Phase 2's diagnostics arms inherit the same silence-on-`Previous` trade — see the table in Diagnostics below.

### Hovers: directive prose + arg prose from the snapshot

`Hovers.compute` grows an `LspSchemaSnapshot` parameter (after `CompletionData`, mirroring `Diagnostics.compute`'s phase 1 signature), threaded through the existing two-overload shape:

```java
public static Optional<Hover> compute(WorkspaceFile file, CompletionData catalog,
                                       LspSchemaSnapshot snapshot, Point pos);
public static Optional<Hover> compute(LspVocabulary vocabulary, WorkspaceFile file,
                                       CompletionData catalog, LspSchemaSnapshot snapshot, Point pos);
```

The hover-resolution path branches twice:

1. *Cursor on the directive's name* (`@auth` token itself). Today this is a no-op because `vocabulary.coordinateAt` returns no coordinate for the directive-name token: the bundled vocabulary has no `SchemaCoordinate.Directive` shape, only arg coordinates. Phase 2 adds a directive-name hover path that runs *before* the coordinate lookup: if the cursor sits on the directive's name node, resolve through `DirectiveResolution.resolve` and produce a hover from `Bundled.def().getDescription()` or `User.shape().description()`. The bundled path lights up free hovers on the seventeen graphitron directives in `directives.graphqls` (a small phase 2 side-benefit, matching the SDL-docstring fallback already documented in `Hovers.java`'s class javadoc); the user path is the headline.

2. *Cursor on a known coordinate* (today: `@auth(role: <here>)` — `role` is the arg name node, not a value). The existing `richerHover` switch is keyed on `Behavior` arms, so it lights up only for coordinates the overlay has. Phase 2 adds a `User`-arm fallback to `docstringHover` that reads the arg's description from the snapshot's `DirectiveShape.args[i].description()` when the bundled vocabulary returns no description. The shape uses the same sealed-switch pattern: try the bundled `descriptionOf(coord)` path first; on miss, walk the user snapshot.

The implementation extends `LspVocabulary.descriptionOf` to take a `DirectiveResolution.User` arg, or — cleaner — adds a small helper next to `Hovers` (`SnapshotHovers.descriptionOf(DirectiveResolution.User, SchemaCoordinate)`) so the bundled-side `LspVocabulary` doesn't learn about the snapshot. The "module owns what it knows" principle settled in R139 design note 5 points at the second; phase 2 keeps the precedent.

Range computation does not change: the hover's range tracks whichever syntactic node the cursor is on, identically for bundled and user paths.

### Diagnostics: top-level unknown-arg + required-arg on user directives

`Diagnostics.compute`'s signature does *not* change in phase 2. The existing `LspSchemaSnapshot` parameter from R139 is already there; phase 2 expands the body of the existing `Bundled` arm to share its arg-validation surface with a new `User` arm.

The diff is in `Diagnostics.compute`'s switch:

```java
case DirectiveResolution.Bundled bundled -> {
    var dirDef = bundled.def();
    validateUnknownArgs(directive, dirDef, vocabulary, file, out);    // unchanged
    validateRequiredArgs(directive, dirDef, file, out);                // unchanged
    // ... leaves / dispatch / legacy name leaves: unchanged
}
case DirectiveResolution.User user -> {
    // New in phase 2. Snapshot-driven arg validation. Top-level only;
    // nested validation needs an input-type projection the snapshot does
    // not yet carry (see "Future evolution").
    validateUnknownArgsAgainstSnapshot(directive, user.shape(), file, out);
    validateRequiredArgsAgainstSnapshot(directive, user.shape(), file, out);
}
```

Implementation note: the snippet above is the *logical* shape of the warn arms. The actual `Diagnostics.compute` body nests freshness over resolution rather than the other way around: an outer `switch (snapshot)` for Unavailable / `Built.Previous` / `Built.Current`, and an inner `switch (resolution)` only on the `Built.Current` arm where the warn arms actually fire. Two reasons. First, the silence policy is freshness-driven, not resolution-driven (R139's table at `lsp-schema-snapshot-side-channel.md:222-229`, extended below): both the User arm and the Unknown arm silence under Unavailable + `Built.Previous`, so factoring freshness to the outer switch makes the 2-D table visible. Second, a future variant on `LspSchemaSnapshot` (the next widening already named in "Future evolution" is `Built` growing user-input-object shapes) forces a compile-time policy decision on every emitter — the flat alternative would inline the freshness check inside each resolution arm and silently grow an unreachable arm on every future widening. The bundled arm runs regardless of snapshot freshness and lives outside the nested switch as an early `if (resolution instanceof Bundled) { ...; continue; }`.

Two new package-private helpers next to the bundled equivalents. Each mirrors the bundled shape; the diff is the input type (`DirectiveShape` vs `DirectiveDefinition`) and the source of the arg list (`shape.args()` of `InputValueShape` vs `dirDef.getInputValueDefinitions()` of `InputValueDefinition`):

* `validateUnknownArgsAgainstSnapshot` walks every top-level arg the user wrote and flags any name not in `shape.args()`. The arg-name match is case-sensitive (same as the bundled path's `LspVocabulary.findInputValue`, which uses `String.equals`).

* `validateRequiredArgsAgainstSnapshot` walks `shape.args()` and flags any whose `type()` is non-null (`InputValueShape.type() instanceof TypeShape.<X> x && x.nonNull()`) and not present in the user's call. The non-null check is one method call, since `TypeShape` is sealed and both permits expose `nonNull()`.

The freshness gate applies the same way as R139's unknown-directive arm. The arg-validation table parallels R139's unknown-directive table (`lsp-schema-snapshot-side-channel.md:222-229`); both arms gate on the snapshot variant identically, with R139's arm warning on `Built.Current + Unknown` and phase 2's arm warning on `Built.Current + User + arg-shape miss`:

| Snapshot variant     | Bundled has the name | Snapshot has the name | Arg-validation outcome                                              |
|----------------------|----------------------|-----------------------|---------------------------------------------------------------------|
| any                  | yes                  | (n/a)                 | bundled arg validation (current Bundled-arm path; unchanged)        |
| `Unavailable`        | no                   | (n/a)                 | silence (no snapshot to consult)                                    |
| `Built.Previous`     | no                   | yes                   | silence (stale; same conservative principle as R139 phase 1)        |
| `Built.Previous`     | no                   | no                    | silence (stale; no info either way)                                 |
| `Built.Current`      | no                   | yes                   | snapshot arg validation (new in phase 2; warn on typo/required-miss)|
| `Built.Current`      | no                   | no                    | the R139 unknown-directive arm fires (no change here)               |

The silence-on-`Previous` policy is the same trade R139's phase 1 unknown-directive arm took, applied symmetrically: a typo introduced in the same edit that broke the parse stops warning until the user fixes the parse error. Acceptable because the parse error dominates the surface, and the seal makes the policy a one-arm edit away if experience says otherwise.

Nested unknown-field validation (`@key(fields: {neme: "id"})` style typos inside a user-directive's nested object literal) is deliberately out of scope for phase 2 and called out in "Future evolution". Implementing it requires the snapshot to carry user-declared input-object-type shapes, which is a follow-on producer-side widening, not part of this item's payload.

### ArgNameCompletions: directive args from the snapshot

`ArgNameCompletions.generate` grows an `LspSchemaSnapshot` parameter (`generate(LspVocabulary, LspSchemaSnapshot, Directives.Directive, Point, byte[])`). The top-level arg-name path's current short-circuit on `dirDef.isEmpty()` is replaced with a `DirectiveResolution.resolve` call; the snapshot's `User.shape().args()` produces the same `List<CompletionItem>` shape the bundled path produces today:

```java
var resolution = DirectiveResolution.resolve(vocabulary, snapshot, directiveName);
return switch (resolution) {
    case DirectiveResolution.Bundled b ->
        topLevelOrNested(b.def(), vocabulary, ...);                    // existing path
    case DirectiveResolution.User u ->
        topLevelFromSnapshot(u.shape(), enclosing);                    // new in phase 2
    case DirectiveResolution.Unknown ignored ->
        List.of();
};
```

Top-level completion only: when the cursor sits at a nested-object-arg slot on a user directive (the same nested-input-type chain the bundled path walks via `LspVocabulary.unwrapToInputTypeName`), phase 2 returns an empty completion list rather than guessing. This is the symmetric trade with the nested-arg-validation deferral in Diagnostics: both depend on the same missing input-type projection. When the snapshot carries input-type shapes (future evolution), both arms light up together.

User-directive completions read identically across `Built.Current` and `Built.Previous`: completion always prefers stale info over silence, since a freshly-typed name suggestion is more useful than nothing even when the snapshot may be slightly out of date.

### LSP-side helper: `SnapshotProjection` (or inline)

The three consumers each consult `DirectiveShape` differently — Hovers wants the description, Diagnostics wants the arg list with non-null detection, Completions wants the arg-name list. The shape is small enough that each consumer reads `shape.args()` directly; no shared helper module. If a fourth consumer surfaces and the access pattern repeats, lift then.

The one shared concept is `TypeShape.nonNull()` for required-arg detection. `TypeShape` is sealed with `Named` and `List` permits; `nonNull()` is declared on the interface and both permits expose it. No new helper.

---

## Implementation sites

The file-by-file diff:

* `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/hover/Hovers.java`: grow `compute`'s two overloads with an `LspSchemaSnapshot` parameter after `CompletionData`. Pre-resolve through `DirectiveResolution.resolve` once at the top of `compute`. Add a directive-name-hover branch *before* the coordinate-lookup path: if the cursor sits on `directive.nameNode()`, produce a hover from the bundled directive's description or the user shape's description. After the coordinate-lookup path, add a `User`-arm fallback that reads `InputValueShape.description()` from the snapshot. The User-arm fallback is gated on `resolution instanceof DirectiveResolution.User` (not on a direct `LspSchemaSnapshot.Built` cast) so bundled directives' missing arg descriptions stay empty rather than leaking through to a shadow snapshot entry (R139 settled design note 4).
* `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/diagnostics/Diagnostics.java`: in the existing snapshot-aware switch in `compute`, replace the silent-fall-through `User` arm with calls to `validateUnknownArgsAgainstSnapshot` and `validateRequiredArgsAgainstSnapshot`. Add the two helpers as package-private statics next to their bundled counterparts. No change to `compute`'s signature; the `LspSchemaSnapshot` parameter is already present.
* `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/completions/ArgNameCompletions.java`: grow `generate`'s signature with `LspSchemaSnapshot` after `LspVocabulary`. Replace the `dirDef.isEmpty()` short-circuit with `DirectiveResolution.resolve` + sealed switch; route the `User` arm through a new `topLevelFromSnapshot(DirectiveShape, Directives.Argument)` helper that returns `CompletionItem` instances mirroring `toCompletionItems`'s output.
* `graphitron-rewrite/graphitron-lsp/src/main/java/no/sikt/graphitron/lsp/server/GraphitronTextDocumentService.java`: thread `workspace.snapshot()` through the existing `Hovers.compute` and `ArgNameCompletions.generate` callsites (`:147` and `:187`). The `Diagnostics.compute` callsite at `:111` already passes the snapshot; no change there.

R139's phase 1 prep-note about `Diagnostics.compute` parameter-list pressure dissolves here: phase 2 does *not* add another parameter to `compute`. The `LspSchemaSnapshot` parameter is already in scope. Decision deferred again until something actually needs a fifth parameter; no Request/Context record needed in this item. The note stays on R139's body as a deferred follow-on.

R139's prep-note about the cross-module audit gap: phase 2 does *not* add new `@DependsOnClassifierCheck` markers after all. The existing marker on `Diagnostics.compute` is load-bearing because the unknown-directive arm warns under `Built.Current` and silences under `Built.Previous` (it depends on the classifier guarantee "Built means clean parse"). Phase 2's new arg-validation arms inherit that same dependency through the same `compute` body, so one marker still covers them. `Hovers.compute` and `ArgNameCompletions.generate` are freshness-agnostic by design — both surfaces prefer stale info over silence — so no classifier guarantee is load-bearing for them, and the markers would be aspirational rather than functional. The R139 prep-note's "two more markers" expectation was wrong in spirit; this body amends it. The audit-widening decision (still tracked in R139's "Future evolution") is unaffected.

R139's prep-note about `DevMojo` bootstrap shape: phase 2 does *not* add a new producer-side projection, so the bootstrap-shape pressure doesn't change. The note stays deferred.

---

## Tests

Four tiers, structurally identical to R139's coverage shape.

### Unit-tier

No new unit-tier tests are needed for phase 2's consumer arms — each is a thin walker over the same `DirectiveShape` / `InputValueShape` / `TypeShape` records `CatalogBuilderSnapshotTest` already pins. The seal exhaustiveness on `DirectiveResolution` and `TypeShape` is `javac`-checked.

If a regression surfaces during implementation that the unit tier can pin without bringing in a `WorkspaceFile`, add it next to `LspSchemaSnapshotTest`. None known up-front.

### Pipeline-tier

New tests live next to their consumers, following the existing `DiagnosticsTest` / `HoversTest` / `ArgNameCompletionsTest` patterns:

* **`HoversTest`** (new cases):
  - `userDeclaredDirectiveNameHover_returnsSnapshotDescription` — cursor on `@auth` of `@auth(role: "admin")`, snapshot has `DirectiveShape("auth", ..., description=Optional.of("..."))`, returns a hover with that description.
  - `userDeclaredDirectiveArgHover_returnsSnapshotArgDescription` — cursor on the `role:` arg-name node, hover returns the `InputValueShape("role", ..., description)` description.
  - `userDirectiveHoverUnderUnavailableSnapshot_returnsEmpty` — pre-build state, no hover (no snapshot to consult).
  - `userDirectiveHoverUnderPreviousSnapshot_stillReturnsContent` — stale snapshot, hover still fires (stale-prefers-over-silence policy for hovers).
  - `bundledDirectiveNameHover_returnsBundledDescription` — pins the bundled-name hover side benefit (small free win from the directive-name hover path).

* **`DiagnosticsTest`** (new cases, following the R139 patterns):
  - `userDirectiveUnknownTopLevelArg_warns` — `@auth(rle: "admin")` against a snapshot carrying `@auth(role: String!)`, warns on `rle`.
  - `userDirectiveMissingRequiredArg_warns` — `@auth` (no args) against the same snapshot, warns on the missing required `role`.
  - `userDirectivePresentRequiredArg_silent` — `@auth(role: "admin")` against the same snapshot, no diagnostics.
  - `userDirectiveUnknownArgUnderUnavailableSnapshot_silent` — pre-build state silences the warn arm (same policy as phase 1's unknown-directive arm).
  - `userDirectiveUnknownArgUnderPreviousSnapshot_silent` — stale snapshot silences (R139's silence-on-`Previous` trade carried forward).
  - `bundledArgValidationStillFires_evenWhenSnapshotShadows` — collision case from R139's table (`Built.Current` carries a shadow `@table` with different args). The bundled arg validation must still run for `@table`; the snapshot's args do not leak into the bundled path.

The shadow-precedence guard is mirrored in the other two suites: `HoversTest.bundledDirectiveArgHover_ignoresSnapshotShadow` (cursor on a shadow-only arg-name returns empty rather than reading through to the shadow's description) and `ArgNameCompletionsTest.bundledDirectiveShadowedBySnapshot_routesThroughBundledPath` (top-level completion lists the bundled arg set, not the shadow's). Symmetric coverage across all three consumers pins R139 settled design note 4.

* **`ArgNameCompletionsTest`** (new cases):
  - `userDirectiveTopLevelArgCompletion_emitsSnapshotArgs` — cursor inside `@auth(<here>)`, snapshot has `@auth(role:, scope:)`, returns both as completion items.
  - `userDirectiveNestedArgCompletion_returnsEmpty` — cursor inside `@auth(input: { <here> })` against a snapshot whose `role` arg is typed `String!` (not an input type), returns empty (the nested-input-type projection is not in scope yet).
  - `userDirectiveUnderUnavailableSnapshot_returnsEmpty` — pre-build state silences (current behaviour, preserved).
  - `userDirectiveUnderPreviousSnapshot_emitsArgs` — stale snapshot still suggests (completion-prefers-stale policy).

Existing tests for the bundled paths in all three suites stay unchanged; the snapshot parameter is threaded with `LspSchemaSnapshot.unavailable()` where the test does not exercise the user-directive arm.

### Compilation-tier

The R139 sakila fixture (`graphitron-sakila-example/.../schema.graphqls:6-8`) already declares `@auth(role: String!)` and applies it on `Query.customers`. Phase 2's compilation-tier check is the same in spirit: the build pipeline accepts the user directive's declaration and its single application without rejecting either. R142 ships *no* schema-fixture additions; the R139 fixture remains the regression guard for the input contract, and phase 2 does not need a richer fixture (no new user-visible code is generated). If a phase 2 implementation detail surfaces a contract the existing fixture doesn't cover (e.g. multi-arg user directive, nullable + non-null arg mix), add a focused unit-tier registry fixture to `CatalogBuilderSnapshotTest` rather than the sakila schema.

### Execution-tier

Not applicable: the LSP's behaviour is observable in the diagnostic / hover / completion outputs (pipeline tier). No runtime path is exercised.

---

## Acceptance

The four observable behaviours, in user terms:

1. **Hover on `@auth`** (a user-declared directive name) shows the directive's description from the snapshot, plus arg-list line. Hover on a user-arg name (`role:` in `@auth(role: ...)`) shows the arg's description and type.
2. **Typing `@auth(rle: "admin")`** (typo in the arg name) produces a warning on `rle`, citing `@auth` as the directive, identical in shape to the bundled-arg typo warning today.
3. **Typing `@auth(<cursor>)`** produces completion items for the directive's args (`role`, plus any others the snapshot carries), each rendered with `CompletionItemKind.Field` to mirror the bundled output.
4. **Omitting a required arg** (`@auth` with `role: String!` required) produces a "Missing required argument 'role'" warning on the directive's name node.

The four arms above are all gated on `Built.Current`: pre-build (`Unavailable`) and post-parse-failure (`Built.Previous`) silence the warn arms but keep hovers / completions reading from the stale snapshot (since stale info is more useful than no info for those surfaces). Bundled directives are unaffected; the existing `Diagnostics` / `Hovers` / `ArgNameCompletions` behaviour on graphitron's seventeen built-in directives is preserved verbatim.

---

## Non-goals

* *Nested unknown-field validation on user directives.* `@key(fields: {neme: "id"})`-style typos inside a user-directive's nested object literal are out of scope. Reaching the field name requires the snapshot to carry input-object-type shapes (today it ships directive shapes only). Tracked in "Future evolution".
* *Nested arg-name completion on user directives.* Symmetric with the previous bullet; deferred to the same future widening.
* *Hover on user-declared input-type field references.* If a user directive has `arg: SomeInputType` and the user types `arg: { <cursor> }`, hovering on a nested input-type field is out of scope. Same input-type-projection dependency.
* *User-directive arg-mapping or class-name overlay.* The bundled overlay (catalog-table binding, class-name completion, method-name resolution) keys on bundled-directive coordinates; surfacing equivalent overlay behaviour on user directives is a different design (the user declares it; the LSP cannot infer the semantic). Not contemplated.
* *Shadow-warning for user directives that redeclare bundled names.* Already a non-goal in R139's spec body (settled design note 6); inherits.

---

## Future evolution (out of scope)

* *Project user-declared input-object types into the snapshot.* The natural next widening: `LspSchemaSnapshot.Built` grows `inputObjectTypes: List<InputObjectShape>`, where `InputObjectShape` carries `name` + `List<InputValueShape> fields`. Producer ships the projection from the same post-merge registry. Two consumers light up in the same commit: the nested-unknown-field arm in `Diagnostics` (today's `descendUnknownArgs` at `Diagnostics.java:156-183` extends to read the user input-type's fields when the bundled vocabulary's `getTypeOrNull` returns null) and the nested-arg-completion path in `ArgNameCompletions` (today's `collectEnclosingFieldChain` walk at `:142-167` extends through user-declared input types). The seal makes the widening additive: a new field on `Built`, not a new permit. Defer until a real consumer surfaces; current symmetric silence is acceptable.
* *Lift the directive-name hover branch into `LspVocabulary` after a third consumer wants it.* Phase 2 adds the directive-name hover as an inline check in `Hovers.compute`. If a future feature (e.g. "go to definition on a user directive" or "rename across uses") wants the same name-as-coordinate concept, lift it; until then the inline check is two lines and not worth a coordinate-system change.
* *Widen `LoadBearingGuaranteeAuditTest`'s scan*. Phase 2 increases the find-usages-only `@DependsOnClassifierCheck` count on the LSP side from 2 (R139) to ~5 (`Hovers.compute`, `Diagnostics`' new helpers, `ArgNameCompletions.generate`). The audit's coverage gap is not phase 2's payload — it is its own design call (cross-module test-classpath aggregation), tracked at R139's "Future evolution" entry of the same name.
* *Re-evaluate the silence-on-`Built.Previous` policy* across all four arms (R139's unknown-directive + phase 2's three arms). Same `flip-the-switch-arm` mechanic: javac demands the same arm-set on every consumer, so a policy change lands as one local edit per file. R139's spec body already names this; phase 2 inherits it. Wait for user feedback after phase 2 lands before tightening or relaxing.

---

## Implementer notes

* The R139 phase 1 prep-note about a `Diagnostics.Request` record is *not* triggered by phase 2: the existing `LspSchemaSnapshot` parameter already covers what phase 2 needs. Leave the parameter list as-is; the note stays on R139's body as deferred.
* The two new diagnostic helpers should sit next to `validateUnknownArgs` / `validateRequiredArgs` in `Diagnostics.java` (package-private statics). Naming with the `AgainstSnapshot` suffix keeps grep-ability when a future contributor needs to remove or refactor one source of validation without disturbing the other.
* `ArgNameCompletions.generate`'s top-level path is the only one that branches on snapshot; nested-arg completion silently no-ops on user directives (covered by `userDirectiveNestedArgCompletion_returnsEmpty`). Resist the urge to half-implement nested completion against a partial input-type shape — the missing data is real and the symmetric silence is correct.
* `Hovers.compute`'s new directive-name hover path comes *before* `coordinateAt`. Today the coordinate system is leaf-oriented (arg coordinates, not directive coordinates); the directive-name token is at byte offset 0 of the directive node but is not a coordinate in `LspVocabulary`. Either treat the directive-name token as a special pre-step in `Hovers.compute` (recommended) or introduce a `SchemaCoordinate.DirectiveName` permit (heavier, drags coordinates everywhere). The pre-step is local and reversible.
