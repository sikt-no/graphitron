---
id: R753
title: "Accept @deprecated alias fields sharing a write column on jOOQ-record inputs"
status: Spec
bucket: bug
priority: 4
theme: classification-model
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# Accept @deprecated alias fields sharing a write column on jOOQ-record inputs

Renaming a field in a published GraphQL API follows a standard deprecation pattern: add the
new field, keep the old one marked `@deprecated` with a removal date, and point both at the
same database column so either name works during the deprecation window. Graphitron 9
supported this on write inputs; the rewrite rejects it. `InputBeanResolver.buildJooqRecord`
fails classification when two plain `@field` leaves resolve to one column ("two fields cannot
populate one column"), on the stated ground that the overlap "would last-write-wins silently".
That justification does not hold on the jOOQ-record path: `JooqRecordInstantiationEmitter`
wraps every column assignment in a `containsKey` presence guard, so an unsent alias never
touches the record; when a client sends both names, the later declaration wins, which is the
deprecation semantics authors expect. The rejection is unavoidable for affected schemas: the
deprecated field cannot be removed early without breaking the published contract, and there is
no other column to point it at, because sharing the column is the intent. Live case:
fs-plattformen's sis schema renamed `antallPlasser` to `antallPraksisplasserOnsket`, both on
column `TALL_ONSKET_PLASSER` of `UNDERVISNINGSAKTIVITET`, with the old name published as
removable no earlier than 2028-03-31; that schema cannot build on the rewrite today.

The resolution: admit the overlap when it is a declared alias (all but at most one of the
colliding fields carry `@deprecated`), keep rejecting undeclared collisions so the diagnostic
still catches accidental duplicates, and represent an admitted alias group in the model as
what the author actually declared: one column written through several names, not several
writers racing for one column. Reusing the runtime value-agreement path instead would be
wrong semantics: a client sending `old: 20, new: 30` should get 30, not an error about the
two disagreeing.

---

## Where the rejection lives, and which siblings stay

Three build-time rejects guard plain-writer column overlap on write paths; this item relaxes
exactly one of them.

| Site | Path it guards | Fate in this item |
|---|---|---|
| `InputBeanResolver.buildJooqRecord` (the per-column fold over `CallSiteExtraction.ColumnBinding`) | `@service` method param typed as a jOOQ table record; runtime is `JooqRecordInstantiationEmitter`'s presence-guarded loads | **Relaxed** per the acceptance rule below |
| `MutationInputResolver.rejectPlainColumnCollision` | INSERT mutation inputs; runtime routes through SET-map puts | Still rejects; message re-grounded on the SET-map clobber; candidate follow-up item once its emitter semantics are analysed |
| `UpdateRowsWalker` stage 6b (`UpdateRowsError.PlainColumnCollision`) | UPDATE mutation inputs; single-row SET map, and the bulk path's VALUES-join crashes outright on a duplicate derived column | Still rejects; message re-grounded on its mechanism; the bulk-path crash makes a blanket relax unsound |

The member-axis reject on plain-Java-bean inputs (`indexSdlFields`, "both bind to Java
member") also stays: that path emits unguarded setter calls, so two fields on one member
genuinely clobber each other regardless of what the client sent. Decode-involving overlaps
(`@nodeId` vs anything) keep their existing deferral to the runtime value-agreement check on
every path.

## Design

### Acceptance rule

A column written by two or more plain `@field` leaves is **admitted when all but at most one
of the colliding fields carry `@deprecated`**, and rejected otherwise. Two live
(non-deprecated) fields on one column stay an author error; a rename chain that has produced
several deprecated aliases plus one live field is fine; so is a transitional state where
every writer of a column is deprecated.

Deprecation means the native `@deprecated` directive on the SDL leaf
(`GraphQLInputObjectField.isDeprecated()`, already consumed by `InputTypeGenerator`). On this
surface that is the only spelling, so the rule needs no disambiguation between two markers.
`DeprecationRecognizer` is a different surface and is not consulted: it backs the
`no-deprecated-directive-usage` lint, where `inputFieldDeprecation` reads native
`@deprecated` off the input types of graphitron's own directives, and its docstring
convention (`directiveDeprecation`) covers whole directive definitions, which native
`@deprecated` cannot carry.

### One writer with ordered read paths, not two writers on one column

An admitted alias group does not survive into the model as multiple bindings. At classify
time, `buildJooqRecord` merges the group into **one** `CallSiteExtraction.ColumnBinding`
carrying an ordered list of read paths (today's single `path` becomes the degenerate
one-element case). Precedence is a stated component, not an accident of list position: the
live path first, then the deprecated paths in reverse declaration order (latest declaration
first); an all-deprecated group orders entirely by reverse declaration. The emitted load for
such a binding is one presence-guarded assignment that tries the paths in precedence order
and takes the first present one, so a client sending both `old` and `new` gets the live
value, a client sending only `old` still writes, and a client sending neither leaves the
column `changed=false`, preserving the jOOQ changed-flag contract per column rather than per
alias.

This is the shape the SDL fact actually has: the author declared one column reachable
through two names. It also means no all-plain shared column ever reaches
`JooqRecordInstantiationEmitter`: `ColumnOverlap.groupByColumn` sees at most one plain
writer per column, the `emitWithAgreement` branch keeps its invariant that every overlap reaching it involves a
`@nodeId` decode (its `orElseThrow` on the encoder class stays sound; the comment there
should name the classify-time merge as the reason), and generated output for schemas without
alias overlap is unchanged. The multi-path component is body-affecting, so
`JooqRecordHelperNames.canonicalRender` must render every read path, keeping helper
dedup/contention identity honest.

### The collision fold reads the shared grouping

`buildJooqRecord`'s hand-rolled `byColumn` `LinkedHashMap` is replaced by
`ColumnOverlap.groupByColumn`, the same fold `MutationInputResolver`,
`TypeFetcherGenerator`, and `JooqRecordInstantiationEmitter` already read, so the
classifier's admission predicate and the emitter's dispatch consume one grouping by
construction. Per group: not shared, pass through. Shared: the acceptance rule applies to
the group's **plain** writers whether or not a decode also lands on the column, so this fold
does not reuse the sibling paths' `shared() && allPlain()` reject predicate verbatim. Two or
more live plain fields reject (the current `@service` fold already rejects a plain-plain
overlap even when a decode shares the column; that guard survives). A plain subgroup the
acceptance rule admits merges into the multi-read-path binding. A decode among the writers
changes nothing about the plain-subset dispatch; the decode-vs-plain overlap keeps its
existing runtime value-agreement deferral, with the merged binding contributing its
first-present value in precedence order to the agreement check (this is what makes the
agreement path's read-path iteration under Implementation sites load-bearing, not dead
generality).

### The surviving rejection becomes a typed arm

The `@service`-path collision reject is today an untyped `Rejection.structural` string; its
INSERT and UPDATE siblings are respectively untyped prose and a typed arm with an LSP code
(`UpdateRowsError.PlainColumnCollision`). The surviving reject (two or more live fields on
one column) becomes a typed arm on the `@service` axis, sibling to `ServiceMethodCallError`'s
family (exact seal home per that file's sibling sub-seal note), carrying the colliding
field paths, the column, the table, and each side's deprecation status, with an `lspCode()`.
The rendered message teaches the carve-out as a third remedy: "... remove one, point its
`@field(name:)` at a different column, or mark the superseded field `@deprecated` to declare
it an alias".

### Sibling messages state their real grounds

Admitting the pattern on one path makes the shared prose "two fields cannot populate one
column" false as a general rule. The two untouched rejects get their messages re-grounded in
their actual mechanism so they read as named deferrals, not unawareness:
`MutationInputResolver.rejectPlainColumnCollision` names the SET-map clobber, and
`UpdateRowsError.PlainColumnCollision` names the single-row SET-map clobber plus the bulk
VALUES-join duplicate-derived-column crash. Both may note that the deprecated-alias pattern
is currently supported only on `@service` jOOQ-record params.

## Implementation sites

- `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/CallSiteExtraction.java`:
  `ColumnBinding` moves from one `path` to an ordered read-path list (single-path stays the
  common case; compact constructor validates non-empty paths and distinct path sets; javadoc
  states precedence semantics). Keeping a derived primary-path accessor is worth considering:
  the existing `columnBindings()` assertions in `JooqRecordServiceParamPipelineTest` read
  `path()`, and a primary is what the emitter's single-path helpers below need anyway.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/InputBeanResolver.java`:
  `collectJooqBindings` gathers per-leaf deprecation alongside path and column (gathering
  state, dies before the carrier); `buildJooqRecord` folds through
  `ColumnOverlap.groupByColumn`, merges admitted groups, rejects two-live groups via the new
  typed arm.
- A typed rejection arm replacing the untyped `Rejection.structural` at the collision site. Its
  home is a **new sibling sub-seal** of `Rejection.AuthorError`, not an arm on
  `ServiceMethodCallError`: that seal's javadoc scopes it to `ServiceMethodCallWalker`, and this
  reject is minted in the classify phase by `InputBeanResolver`, so folding it in would break the
  one-producer-per-seal scoping its own sibling sub-seal note asks for. Suggested name
  `JooqRecordInputError` with the `graphitron.jooq-record-input.` `lspCode()` namespace; the name
  is the implementer's call, the registration surface below is not.
- Registering a new sub-seal is a closed, build-enforced surface. Six sites move in the same
  commit:
  - `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/Rejection.java`: add the sub-seal
    to the `AuthorError` `permits` clause.
  - `graphitron/src/main/java/no/sikt/graphitron/rewrite/diagnostics/RejectionFacts.java`: a
    `case <Seal> e -> coded(e.lspCode())` arm in `typedColumns`. That switch has no `default`, so
    omitting it is a compile error rather than a silent NULL column.
  - `docs/architecture/explanation/typed-rejection.adoc`: a paragraph naming the new leaf.
    `SealedHierarchyDocCoverageTest` fails both directions (a leaf with no prose, prose with no
    leaf), so this is authoring work, not a mechanical fill-in.
  - `graphitron/src/test/java/no/sikt/graphitron/rewrite/diagnostics/RejectionResidueDrainageTest.java`:
    add the leaf to `RESIDUE_LEAVES`, which is asserted as an exact set, and correct the
    "nine lspCode()-bearing sub-seals" count in the comment above it.
  - `graphitron-lsp/src/test/java/no/sikt/graphitron/lsp/RejectionSeverityCoverageTest.java`: a
    `sampleFor` sample for the new arm, else the permit reports as unmapped.
  - `graphitron/src/test/java/no/sikt/graphitron/rewrite/HierarchyKindRegistryTest.java`: a kind
    label, since the scan requires every top-level sealed type in scope to be labelled once.

  If the implementer lands the arm on an existing sub-seal after all, only that seal's `permits`
  clause plus the `typed-rejection.adoc`, drainage and severity-coverage entries apply.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/JooqRecordInstantiationEmitter.java`:
  `emitColumnBinding` and `emitPrepare` (the agreement path's plain-writer read) iterate the
  binding's read paths in precedence order, first-present wins, where "present" is the existing
  `containsKey` guard: an explicitly-sent `null` on the winning path still writes SQL NULL and
  marks the column touched, exactly as a single-path binding does today. `emitLoadPrepared` needs
  no change, since it reads the local `emitPrepare` already resolved. Four helpers assume one path
  per binding and need a stated primary: `Writer.path()`, `WriterView.label()` (the agreement
  message label), `localBase(cb.path())` (the local-name base), and `ColumnBinding.leaf()`. The
  `emitWithAgreement` encoder-availability comment re-grounds on the classify-time merge.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/JooqRecordHelperNames.java`:
  `canonicalRender` renders every read path.
- `graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java` and
  `graphitron/src/main/java/no/sikt/graphitron/rewrite/model/UpdateRowsError.java`: message
  re-grounding per the Design section (text only, no behavioural change).
- Javadoc re-grounding, no behavioural change, same commit. Six live claims say the thing this
  item narrows, and stop being blanket truths once the `@service` path merges an admitted alias
  group instead of rejecting it:
  - `model/ColumnOverlap.java`, three places: the class javadoc and the `ColumnWriter.decode()`
    javadoc both say "an all-plain overlap is a build-time reject", and `OverlapColumn`'s javadoc
    calls `allPlain()` "the validator's build-time reject when also shared".
  - `MutationInputResolver.rejectPlainColumnCollision`'s javadoc calls itself "the mutation-path
    mirror of the `@service` reject".
  - `model/UpdateRowsError.java`'s `PlainColumnCollision` javadoc calls itself "the UPDATE mirror
    of the INSERT-path / `@service` reject".
  - `InputBeanResolver.buildJooqRecord`'s own javadoc lists "two plain fields on one column"
    among the shapes that reject structurally.
- Docs touchpoint: whichever user-manual page documents write-input column binding (grep at
  implementation time; no manual page quotes the rejection message today, so the carve-out
  likely earns a short note where `@service` jOOQ-record params are documented, e.g.
  `docs/manual/how-to/external-code.adoc`).

## Tests

Tier names per `docs/architecture/how-to/testing.adoc`. The read-path shape makes the
winner-selection invariant pinnable at the pipeline tier (a model component, not generated
body text), with execution proving the end-to-end behaviour.

- **Pipeline (classification)**, in `JooqRecordServiceParamPipelineTest`: the existing
  `plainColumnCollisionAcrossNesting_rejects` keeps rejecting (two live fields), now
  asserting the typed arm. New cases: deprecated + live on one column classifies to one
  binding whose read paths order live-first; deprecated + deprecated + live orders live
  first then reverse declaration; two live + one deprecated still rejects; an all-deprecated
  group classifies with reverse-declaration precedence; an alias pair split across a nested
  grouping input and a top-level field merges with full access paths; an alias pair sharing
  a column with a `@nodeId` decode still merges into one multi-path binding (the group
  defers to the runtime agreement check, not exempt from the merge); a decode plus two live
  plain fields on one column still rejects; the typed arm carries
  paths, column, table, and deprecation status, and its rendered message names the
  `@deprecated` remedy.
- **Execution**: a `@service` mutation with an alias pair; send old only (old value lands),
  new only (new value lands), both (new value wins), neither (column untouched,
  `changed=false` contract preserved), and the winning path sent as an explicit `null` while the
  loser carries a value (column written NULL, not the loser's value), which pins presence as
  `containsKey` rather than non-null.
- Existing tests asserting the INSERT/UPDATE message prose update with the re-grounded text.

## Out of scope

- Relaxing the INSERT (`MutationInputResolver`) and UPDATE (`UpdateRowsWalker`) mutation
  paths, per the table above; this item only re-grounds their messages. If the alias pattern
  is wanted there, that is a follow-up item with its own emitter analysis (SET-map puts and
  the bulk VALUES-join have different overlap hazards).
- The member-axis Java-bean reject.
- Any change to decode-involving overlap handling or the runtime value-agreement check.
- Warning on alias overlaps on read/output types (already accepted silently; not this item's
  concern).

## Reviewer decisions

The three forks the draft left open, resolved at the Spec review. The design they resolve is
unchanged; each confirms the draft's own pick and records why, so the implementer inherits a
decision rather than a question.

1. **An all-deprecated group does not warn.** Every writer of a column being deprecated is the
   legitimate state a rename chain passes through on its way to removal, and the author has no
   remedy while the removal dates are still in the future, so a warning would be unactionable
   noise on a correct schema. If a signal is ever wanted it belongs on the lint surface
   `DeprecationRecognizer` already serves, not on the classify path.
2. **Precedence stays live-first, not Graphitron 9's later-declared-wins.** Declaration order in
   SDL is an editing accident: a reformat, a field reorder, or schema stitching changes it without
   changing what the author said. Making the resolved value depend on it is the footgun, and
   live-first is also the only order that stays stable when a further deprecated alias is added
   later. Graphitron 9's rule survives where it is the only rule available: an all-deprecated
   group has no live path, so it orders by reverse declaration, which is later-declared-wins by
   construction.
3. **The read-path list stays on `ColumnBinding`; no `AliasedColumnBinding` variant.** Per "Shape
   the type as precisely as the fact allows", a new sub-taxonomy earns its place with a one-line
   note on what it carries that a sibling cannot, and an aliased binding carries nothing a
   one-element path list does not. A variant would instead force every `columnBindings()` consumer
   to fork on cardinality to reach the same fact.
