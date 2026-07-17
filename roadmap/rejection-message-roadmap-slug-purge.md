---
id: R484
title: "Purge transient roadmap references from string literals; drop the planSlug field that renders one"
status: Ready
bucket: cleanup
priority: 14
theme: diagnostics
depends-on: []
created: 2026-07-15
last-updated: 2026-07-17
---

# Purge transient roadmap references from string literals; drop the planSlug field that renders one

## What this is (revised from the Backlog framing)

The Backlog stub framed this as purging roadmap-slug citations from a handful of rejection/deprecation string literals, a sibling to R482 (comment/javadoc purge). Investigation showed two things: the dominant single case is a designed typed channel, and the broader case is a whole habitat R482's guard deliberately skips.

R482 closed transient roadmap references in *comment and javadoc* regions and left a lexical guard behind. Its scanner strips string, character, and text-block literals before matching, and its own class javadoc names that habitat "user-facing message wording ... tracked separately." R484 is that separate tracker: it closes transient roadmap references (`R<n>` ids and `roadmap/<slug>` paths) in *string-literal* regions across the in-scope modules, so the two items together cover the module. The structural centerpiece is the `Rejection.Deferred.planSlug` field, which does not merely cite a slug in prose but is built to render one onto the author-facing log surface; dropping it is what lets the guard extend cleanly.

`Rejection.Deferred` (`graphitron/.../rewrite/model/Rejection.java`) carries a typed `planSlug` field, and its `message()` renders the summary followed by a `see roadmap/<slug>.md` suffix. So every deferred rejection is built to surface a transient `roadmap/<slug>.md` path on the build-time log surface `GraphitronSchemaValidator` writes to whoever runs the generator. That reader is the SDL author, a graphitron consumer with no `roadmap/` directory, for whom the slug is unreadable and rots the moment the deferred item ships, is renumbered, or is discarded.

## Decision: drop `planSlug`, do not merely hide it

Two shapes were considered: (a) keep `planSlug` as internal metadata but stop rendering it, or (b) drop the field. Nothing navigates the field: an internal thread in `FieldBuilder`'s DML-pair classifier carries it forward to the render, and test assertions read it. There are exactly two render consumers of the suffix, both of which lose it cleanly: `GraphitronSchemaValidator`'s build-time log surface (the author-facing case above), and `TypeFetcherGenerator.stub()` (`:6419`), which renders `STUBBED_VARIANTS.get(...).message()` into the emitted `UnsupportedOperationException` text of a stub method. That stub is validator-unreachable by construction (the validator rejects unimplemented variants at build time), so dropping the suffix only shortens an exception string that should never throw. The javadoc claim that "LSP fix-its read the slug as a typed value and offer 'open the roadmap item'" is a false invariant; no LSP code reads `.planSlug()`. Retaining an unconsumed, unenforced transient pointer is precisely the drift smell this sweep exists to remove.

So `planSlug` is dropped. `stubKey` (the load-bearing `VariantClass` anchor the generator's `STUBBED_VARIANTS` stubbing uses) stays: it is a live class reference, not a slug, so it does not rot the same way. `Deferred.message()` becomes a pure function of `summary`, which structurally cannot render a roadmap path. This serves the project principle that rejections are facts rendered into views, never prose composed at the detection site (`docs/architecture/explanation/typed-rejection.adoc`).

## Scope

### Model

Remove `planSlug` from `Rejection.Deferred`; collapse the `deferred(summary, slug)` and `deferred(summary, slug, class)` factories to `deferred(summary)` and `deferred(summary, class)`; `message()` returns `summary`. Remove the false LSP-fix-it javadoc claim and the `planSlug` field doc. The suffix separator disappears with the suffix. Remove the `FieldBuilder` DML-pair slug-threading (`PairVerdict.Deferred(planSlug)`, the `deferredSlug` fold). Sweep the two non-factory `Rejection.Deferred` constructions that pass a slug: `TypeFetcherGenerator.deferredFor(...)` (`:332`) and its `STUBBED_VARIANTS` `CompositeColumnReferenceField` entry (slug `nodeidreferencefield-join-projection-form`).

### String-literal sweep, three habitats

Every string literal named below rewrites to state the fact, or to name a live symbol / mechanism in plain prose, with no `R<n>` and no `roadmap/<slug>`. In a throw or message string `{@link}` does not render, so a live reference means naming the class/method in prose (for example "the walker classifiers intercept every UPDATE before this point" rather than "R246/R258 intercept ...").

- **A. Author-facing rejection message literals** (`Rejection.structural / deferred / invalidSchema` prose the SDL author meets on the validator log). Sweep, confirming each is author-facing during implementation; any that turns out to be an internal throw belongs to habitat B: `MutationInputResolver` `:369` (deferred; slug in prose and as arg), `:504`, `:605`, `:611`; `FieldBuilder` `:1243`, `:4701`, `:5156`, `:5340`; `GraphitronSchemaValidator` `:251`, `:1157`; `BuildContext` `:2442`; `UpdateRowsWalker` `:241`; `DeleteRowsWalker` `:153`; `InputBeanResolver` `:386`.
- **B. Internal invariant-throw messages** (`IllegalStateException` / `IllegalArgumentException`, fire only on a generator bug; contributor-facing). Many narrate *why* an invariant holds by `R<n>`; restate the invariant naming the live mechanism instead. Sites include the `MutationField` compact-constructor guards (`:259`, `:517`, `:523`, `:606`, `:616`, `:626`), `TypeFetcherGenerator` (`:1822`, `:5924`, `:6307`, `:6313`), `ParentProjectionContainmentCheck` (`:96`, `:109`, `:132`, "generator bug, R425 family"), `RecordBindingResolver:741` ("R96 walker did not converge"), `FieldBuilder` (`:4758`, `:4763`, `:5810`), `MutationInputResolver:483`, `RoutineChain:61`, `ChildField` (`:484`, `:584`, `:592`, `:601`), `JoinPathEmitter` (`:119`, `:194`), `FetcherEmitter:319`, `InlineLookupTableFieldEmitter:262`, `InlineColumnReferenceFieldEmitter:135`, `SplitRowsMethodEmitter:557`. This is a per-site editorial pass, not a blind delete.
- **C. Documentation-emitting generator string literals** (`.addJavadoc(...)` / `.addComment(...)` / `CodeBlock` text that renders into *generated* output). A consumer reading their generated sources meets a stale id they cannot resolve. Sites: `ConnectionRuntimeClassGenerator` (`:499`, `:530`, `:532`, `:568`, `:624`, `:628`; R429/R45), `GraphitronTransactionProviderGenerator` (`:141`, `:233`; R428), `GraphitronDevExecutorGenerator:281` (R428), `GraphitronFacadeGenerator` (`:245`, `:323`; R429), `ErrorRouterClassGenerator:357` (R397), `OneOfDirectiveSdlGenerator:108` (R283), `GraphitronConnectionInstrumentationGenerator:125` (R428), `InputRecordGenerator` (`:150`, `:151`; R150/R172). This habitat subsumes the two Backlog stubs R491 and R493 (see Consolidation).

### Channel-bypass sites

The two `GraphitronSchemaValidator` `@service` / `@externalField` `@reference`-path deferrals that inline the path into the summary with an empty `planSlug` collapse to a plain summary with no path.

### Deprecation warnings

The three synthesis-shim `LOG.warn` sites (`FieldBuilder`, `BuildContext` x2) state the replacement action ("declare `@nodeId` explicitly; the shim will be removed in a future release") with no roadmap path. They are `LOG.warn` string literals, so the extended guard covers them too.

### Tests

Rework the assertions that pin the transient slug or the rendered suffix (`RejectionRenderingTest.deferredWithSlugAppendsRoadmapPath`, `ColumnReferenceFieldValidationTest`, `GraphitronSchemaBuilderTest` two sites, `MultiTablePolymorphicParentHoldsFkPipelineTest` three sites). Where a test discriminates which deferral fired, it asserts on `stubKey` or `summary`, not a slug. The habitat-C edits change *generated* javadoc text, so any generated-output golden / pipeline test that asserts on the emitted javadoc must update to the restated text; this is the "deliberate pass, not a blind sweep" R493 flagged.

### Docs

- **Contributor docs.** In `typed-rejection.adoc`, remove the "LSP fix-its ... open the roadmap item" line and the `planSlug` description. The chapter also names a `StubKey.EmitBlock` arm that no longer exists (only `VariantClass` remains); its twin lives in the `StubKey` javadoc in `Rejection.java` (`:415`). Correct both stale `EmitBlock` sentences.
- **Author-facing docs.** The user manual promises the SDL author a `planSlug:` / roadmap-path suffix in four pages that all go stale: `docs/manual/explanation/classifier-mental-model.adoc` (`:22`, `:67`), `docs/manual/explanation/how-it-works.adoc` (`:43`), and `docs/manual/reference/diagnostics-glossary.adoc` (`:15`, `:18`, `:52`, `:54`, `:56`). Rewrite these to describe the deferred diagnostic with no roadmap-path suffix. `classifier-mental-model.adoc:67` ("the runtime stub ... fails fast with the same plan slug") is the author-facing statement of the stub-render path and must lose the slug claim.
- **Doctrine.** Update the `CLAUDE.md` "Javadoc conventions" note that today says a roadmap id in a user-facing message *string* "is a separate habitat the guard does not police," and the `RoadmapReferenceScanner` class javadoc that calls string literals a habitat "tracked separately," since R484 now polices them.

## Enforcement: extend the R482 guard to string literals

Two pins, one structural and one lexical.

- **Structural** (the typed channel): with `planSlug` gone, `Deferred.message()` is a pure function of `summary` and cannot compose a roadmap path; the compiler carries it.
- **Lexical** (the whole habitat): extend `RoadmapReferenceScanner` with a string-literal projection, a mirror of the existing `commentProjection` that appends the content of `STRING` / `TEXT` regions (the lexer already tracks these states, it simply appends nothing in them today), and run the same `ROADMAP_ID` / `SLUG_REF` patterns over it. `RoadmapReferenceGuardTest` then fails the build on a transient roadmap reference in any string literal across the in-scope modules, one guard covering habitats A, B, and C uniformly. The `changelog / workflow / README` slug allowlist carries over.

False-positive handling: sweep A/B/C first, then turn the string scan on. In this module every `R<n>` found in a string today is a roadmap id, so the residue should be empty; a surviving hit is either a real reference (fix it) or a genuine non-roadmap token (add a narrow, commented allowlist entry). This is the concern R491 named ("without false-positiving on message literals"); it is triaged, not assumed away.

## Consolidation

This item subsumes two Backlog stubs, both surfaced by the R483 audit and near-duplicates of each other:

- **R491** ("Purge roadmap citations from documentation-emitting generator string literals") is habitat C plus the guard-extension question, both now owned here.
- **R493** ("Strip transient roadmap ids from generated-output javadoc") is the same habitat C, named by the same generator sites.

Both are discarded (Backlog file deletion) as part of moving this item to Ready, so the C sites and the guard extension have a single owner. If a reviewer prefers C shipped as its own item, it can be re-filed from this section.

## Done

- `planSlug` removed from the model and every call site; `message()` renders `summary` only.
- No string literal in the in-scope modules renders a transient roadmap reference: habitats A, B, and C are swept, and the deferred summaries that named a slug or `R<n>` in the prose itself (e.g. `MutationInputResolver:369`) carry neither.
- The extended `RoadmapReferenceGuardTest` is green over string-literal regions; the structural pin on `Deferred.message()` holds.
- Contributor and author-facing docs reconciled: `typed-rejection.adoc` (LSP claim and `planSlug` removed), both `StubKey.EmitBlock` sentences corrected, the four user-manual pages no longer promise a roadmap-path suffix, and the `CLAUDE.md` / scanner-javadoc "separate habitat" notes updated to say the habitat is now policed.
- R491 and R493 discarded as subsumed.
- Full `mvn install -Plocal-db` green. Generated-source changes are the shortened validator-unreachable stub `UnsupportedOperationException` text and the restated habitat-C generated javadoc; `compile-spec` still compiles them and `execute-spec` is unaffected (javadoc/exception text is not behavioural). The implementer updates any generated-output snapshot that embeds the changed text.

## Out of scope

- Lifting the deprecation `LOG.warn` diagnostics into a typed deprecation channel (only their roadmap-path residue is swept here).
- Whether deferral anchors more broadly should be non-transient; `stubKey` is a live class reference and does not rot the way a slug does.
