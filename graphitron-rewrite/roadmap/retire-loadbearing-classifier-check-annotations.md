---
id: R237
title: Retire @LoadBearingClassifierCheck / @DependsOnClassifierCheck annotation pair
status: Ready
bucket: architecture
theme: structural-refactor
depends-on: []
created: 2026-05-24
last-updated: 2026-05-25
---

# Retire @LoadBearingClassifierCheck / @DependsOnClassifierCheck annotation pair

## Problem

R21 introduced the `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` annotation pair plus `LoadBearingGuaranteeAuditTest` at commit `9acdf3f` (the infrastructure landed alongside R12 §2c's `error-channel.mappings-constant`, the first live producer key); R230 was a single-site addition (`body-param.nonnull-is-effective-runtime`) to that already-mature convention, not its introducer. The pair has compounded steadily since: 75 sites and 60 keys across the rewrite module today, with `principles-architect` actively pushing new sites in (R232's "fold in arch-agent findings ... load-bearing annotation" is one example). The pattern does not carry the weight its name claims, and its current trajectory institutionalises a documentation drift mode another principle already condemns. The longer the convention has accreted, the stronger the drift-risk argument runs.

The audit catches three drift modes: orphaned consumer (producer renamed/deleted), duplicate producer, and blank `description`/`reliesOn`. The most consequential drift mode — a producer's check is silently relaxed (e.g. `ClassName.equals` widened to `startsWith`, an `instanceof` arm broadened) — is invisible: the key string is still present, the prose description still claims the original guarantee, the audit passes, and the contract is now broken in a way that only surfaces when the generated source fails to compile against `graphitron-sakila-example`. The audit's actual teeth reduce to "did the producer get renamed?", which `git grep` over the key answers equally well.

The verbose `description` / `reliesOn` prose (typically 4–8 lines per site) is exactly the kind of trusted invariant prose that `rewrite-design-principles.adoc § "Documentation names only live tests/code"` warns against. Readers trust the description; nothing pins the description to the code. The pair institutionalises false-invariant drift at scale (60 such descriptions today).

The principle the annotation embodies (classifier acceptance lets the emitter assume narrow shapes) is sound, and the two worked examples in the principles doc (strict `tablemethod` return, `ColumnField` parent table) are real wins. Both are also expressible as type-system contracts, though the lift sizes vary: the strict-tablemethod-return contract is a *relationship* between the field's table token and the method's return token that requires multi-record type-token threading to lift cleanly (see §4 below for the consumer-side detail), while the `ColumnField` parent-table contract is a single-record addition (the `parentTable` is currently a parameter into `TypeFetcherGenerator.generateTypeSpec`, not a record component). Where the lift has already happened (`ScalarResolution.Resolved#javaType` is already a `TypeName`), the annotation is pure duplication. Where it hasn't, the annotation is a band-aid for a missing type-system lift. Either way, the cross-module compile against `graphitron-sakila-example` and the pipeline-tier tests are doing the genuine load-bearing work; the annotation pair is a documentation convention dressed up as a build-time check.

## What we believe is true

The trace that the annotation pair is the wrong enforcement layer rests on five claims, each independently checkable against the codebase as it stands today.

1. **The underlying principle is sound.** Classifier acceptance does let an emitter assume narrower shapes; that's why both worked examples in the principles doc (strict `tablemethod` return, `ColumnField` parent table) catch real bugs. The principle stays. Only the enforcement layer changes.
2. **The annotation's audit teeth are limited to rename / delete of the producer.** `LoadBearingGuaranteeAuditTest` (`graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/model/LoadBearingGuaranteeAuditTest.java:82-113`) groups by `key` and flags orphaned consumers, duplicate producers, and blank prose fields. Nothing else. A silent relaxation of the producer's check body (widening a `ClassName.equals` to a `startsWith`, broadening an `instanceof` arm) passes the audit cleanly so long as the annotation's `key` stays in place.
3. **The genuine safety nets sit downstream.** `rewrite-design-principles.adoc:111` says so directly: the safety net is `mvn compile -pl :graphitron-sakila-example`. The annotation pair is "the cheap upstream version of the same signal." Inspection: the downstream check catches silent relaxation; the upstream audit does not. The cheap upstream signal is weaker than the expensive downstream one, not stronger.
4. **The principle the annotation embodies is expressible in the type system at many of the existing sites.** Sample inspection:
   - `scalar-resolver.javatype-is-typename` (ScalarTypeResolver.java:68-73): the description says "javaType is a JavaPoet TypeName" — and `ScalarResolution.Resolved#javaType` is already typed `TypeName`. The annotation is pure duplication of the signature.
   - `service-catalog-strict-tablemethod-return`: the description says "the developer's method return type equals the generated jOOQ table class". A naive type-system lift would parameterise `MethodRef` on the table type, but the emitter at `TypeFetcherGenerator.buildQueryTableMethodFetcher` (lines 1046–1064) does not read `methodRef.returnType()` — it reads the *field's* `qtmtf.returnType().table()` and emits `<SpecificTableClass> table = MethodClass.method(...)`. The strict check guarantees those two are equal at runtime. Carrying that relationship in the type system requires multi-record type-token threading (parameterise `MethodRef.StaticOnly` and `ReturnTypeRef.TableBoundReturnType` on a shared type token, then thread it through the table arm of the directive resolver). That is structural work with non-trivial blast radius, not the one-line narrowing the original framing implied; jOOQ helper boundaries also accept type erasure per `rewrite-design-principles.adoc § "Selection-aware queries"`, capping how far the bound carries. This key may classify (b-relational) and reclassify to (c) for Phase 3 counting purposes (see Phase 2 below).
   - `column-field-requires-table-backed-parent`: the description says "the classifier only produces a `ColumnField` on a table-backed parent". `ColumnField` today carries no `parentTable` component (its record components are `parentTypeName`, `name`, `location`, `columnName`, `column`, `compaction`); `parentTable` is a *parameter* threaded into `TypeFetcherGenerator.generateTypeSpec` and read at the `case ChildField.ColumnField` arm. The lift would add a non-null `parentTable` record component to `ColumnField` at construction, populated by the classifier, eliminating the parameter and the `IllegalStateException` reachability arm. This is feasible and bounded to a single record + its construction site, but it is not "tighten an existing nullable" — call it (b-cheap, single-record) when classifying.
5. **The verbose `description` / `reliesOn` prose is exactly the failure mode `rewrite-design-principles.adoc § "Documentation names only live tests/code"` warns against.** The current doc states "a javadoc comment saying 'enforced by X' when X does not exist is worse than no comment — it's a false invariant that readers trust." The annotation `description` field claims "the producer rejects X so the emitter may assume Y" — once a producer is silently relaxed, the description becomes a false invariant. At 60 sites today, the pattern institutionalises this failure mode at scale.

The conclusion is that the annotation pair is a documentation convention dressed up as a build-time check. The principle survives; the enforcement layer moves to where it already lives (type signatures, pipeline tests, the sakila-example compile).

## Phased plan

The item ships in up to five phases. Phase 1 (principles revision) is the smallest reversible step that stops the bleeding without touching any of the 75 existing annotation sites; Phases 4–5 retire those sites in separate visibility windows on the Delete/Shrink/Demote branches, or do not run at all if Phase 3 selects Hold. The plan stages this way because the architect agent and reviewer/SRP rubrics actively push the pattern today; the principles edit removes that pressure on day one, so the 60-key classification doesn't grow under us while it runs.

### Phase 1: Principles revision

**Shipped at 96869ab** (2026-05-25). The principles doc, the `principles-architect` agent, and the `reviewer-prompt` / `srp` skills no longer push the `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` annotation pair. "Classifier guarantees shape emitter assumptions" reframed onto three anchors (type-system narrowing at producer, pipeline-tier tests, `graphitron-sakila-example` compile as cross-module backstop) with the two worked examples reframed as candidate type-system lifts. "Documentation names only live tests/code" broadened to cover invariant claims generally. Javadoc `{@link}` recommended as the residual mechanism for producer-consumer linkage records. `dispatch-axes.adoc` cross-axis invariant cites re-anchored on pipeline tests + the cross-module compile. R125 Discarded (superseded; FQN coverage already pinned by R83's pipeline + compile + execute tiers). R186 trimmed: "New audit key" subbullet dropped; the access-path invariant is mechanically pinned by `CallSiteExtraction.NestedInputField`'s sealed-variant carrier and the pipeline-tier coverage step 4 already adds. The 75 existing annotation sites untouched; `LoadBearingGuaranteeAuditTest` still passes.

### Gap-window contract (Phase 1 landing → Phase 4 landing)

- The audit test continues to enforce existing producer/consumer pairing. If a new consumer site organically depends on a load-bearing classifier check at a *pre-existing* producer key, the audit will red-flag a missing `@DependsOnClassifierCheck`. The right response during the window is to add the annotation as if R237 hadn't filed; Phase 4 retires it with the others.
- No new *producer* keys should be filed during the window. If a new emitter assumption emerges that would warrant a new producer key under the old framing, file the type-system lift as a sibling Backlog item instead.

### Phase 2: Classification sweep

Walks each of the ~60 unique keys (the live set comes from `grep -hE '^\s*key = "' graphitron-rewrite/graphitron/src/main/java/...` over annotation sites). For each, classify:

- **(a) Already type-coupled.** The producer's return type / record component / parameter type already carries the narrowness the description claims. Annotation is pure duplication.
- **(b-cheap) Single-record narrow.** Tightening a single producer record / signature would carry the contract structurally, and the consumer compiles unchanged once it reads the narrower component. Example: lift `parentTable` from a parameter to a non-null `ColumnField` component.
- **(b-relational) Multi-record type-token threading.** The contract is a *relationship* between two records (the field's table token equals the method's return token), expressible only by parameterising both records on a shared type token and threading it through every site that constructs or reads either. Higher blast radius. May further reclassify to (c) for Phase 3 counting if the threading turns out structurally expensive or hits a jOOQ-erasure boundary.
- **(c) Cross-module without a compile dependency.** Producer in `graphitron-rewrite/graphitron`, consumer in `graphitron-rewrite/graphitron-lsp` or `graphitron-rewrite/graphitron-sakila-service` (or any downstream consumer project). The compile-link cannot reach across the module boundary.

Each (b-cheap) and (b-relational) key generates a follow-up Backlog item filed during this phase; the lift ships under that follow-up, not R237. The annotation retires under Phase 4 regardless of whether the lift has shipped — the pre-lift signature is fine; once the lift lands, the contract is mechanically enforced.

Methodology:
- For each key, list one row carrying:
  1. producer file:line,
  2. consumer file:line,
  3. current producer return / component type,
  4. what the description claims the contract is,
  5. **what would silently regress at the consumer if the producer's check body relaxed without the key changing?** Empty cell here means the consumer is type-coupled or behaviour-pinned elsewhere; the annotation is duplication regardless of module boundary, and the key reclassifies to (a) / (b-cheap) on that ground.
- Answer "could the producer's signature carry this?". (a) if it already does; (b-cheap) / (b-relational) per the definitions above; (c) if the consumer sits in a module that doesn't compile against the producer.
- Commit the table inline in this Spec file (under `## Classification table`) as a Spec → Spec revision.

Out of scope for Phase 2:
- Executing the per-key lifts. (b-cheap) / (b-relational) entries file Backlog items; the lifts ship under those items.
- Touching the annotations in code.

Phase 2 done means:
- Classification table committed in this Spec. Counts known: `|a|`, `|b-cheap|`, `|b-relational|`, `|c|`, and within `|c|` the count of keys with a non-empty silent-regression column (the "signal-bearing cross-module" subset).
- One Backlog item filed per (b-cheap) / (b-relational) key (titles seeded by the current annotation's `description`).

### Phase 3: Pick Delete / Shrink / Hold / Demote

Decision rule, applied to the Phase 2 counts:

The discriminating axis between Delete and Shrink is *not* the cardinality of (c). Cardinality treats every cross-module pair as equally signal-bearing, but the cross-module documentation link only earns its cost when the consumer would silently regress if the producer relaxed — i.e. when the LSP / sakila-service test suite doesn't exhaustively re-pin the contract the annotation describes. The Phase 2 silent-regression column produces that count directly. Call it `|c-signal|` (subset of `|c|` where the silent-regression cell is non-empty).

Beyond Delete and Shrink, two abort-path branches exist for cases where Phase 4 doesn't earn its keep against Phase 2's actual data: Hold (Phase 1 terminal, audit infrastructure preserved) and Demote (annotations-only without audit). These are not last-resort placeholders; they are first-class outcomes the spec is structured to accommodate. The Phase 2 classification table determines which of the four branches commits.

- **Delete.** Pick when `|c-signal|` is small (rough guide: ≤ 3) AND no surviving signal-bearing pair describes a contract that would mis-emit or mis-validate downstream code without the consumer-module tests catching it. Remove the annotation classes, the `*Checks` containers, `LoadBearingGuaranteeAuditTest`, and the `auditfixture` package. For each retained `|c-signal|` pair, replace the annotation's signal with a *test-side* mechanism (a producer-module test that exercises the consumer's read pattern, or a sibling-module test that loads the producer output). The principles doc already names the cross-module gap (per Phase 1) as a known cost; this carve-out names what fills it where the gap matters.
- **Shrink.** Pick when `|c-signal|` is materially larger (rough guide: ≥ ~8) and per-pair audit shows the test-side mechanism above is consistently more expensive than the documentation link. Rename the annotations to a cross-module-signal-only form (proposed: `@CrossModuleClassifierGuarantee` / `@DependsOnCrossModuleClassifierGuarantee`). Restrict `LoadBearingGuaranteeAuditTest` to scan only the `|c-signal|` set. All in-module pairs and all `|c|` keys with an empty silent-regression cell still retire.
- **Hold (Phase 1 terminal).** Pick when Phase 2's data shows no direction in Delete or Shrink earns its keep against the sunk cost of churning 75 existing sites. Triggering shapes: (b-relational) dominates (b-cheap), AND (b-cheap) is small (rough guide: ≤ 5 keys), AND `|c-signal|` is mid-range (4–7) where neither Delete's test-side replacements nor Shrink's renamed-annotation surface is clearly cheaper than just leaving the existing convention in place. R237 closes at Phase 1's state: the principles revision shipped, the rubrics updated, R125 resolved, and the architect agent / SRP no longer push the pattern. Existing annotation sites and the audit infrastructure stay as-is — archaeological cost accepted in exchange for not touching 75 sites. The Gap-window contract from Phase 1 extends indefinitely: no new producer keys, but existing pairs continue to enforce the rename/delete drift mode the audit was always good at. Commit Hold as a Spec → Spec revision naming the branch and the Phase 2 data that drove the call; Phase 4 and Phase 5 do not run.
- **Demote.** Pick only if Phase 2 reveals that nearly every key is (c) AND the lifts in (b-cheap) / (b-relational) prove infeasible AND the cross-module signal is genuinely load-bearing but doesn't earn audit-infrastructure cost. Rename the audit to documentation-only annotation pair (no `LoadBearingGuaranteeAuditTest`) so the producer/consumer linkage stays IDE-navigable without the build-time scan. Distinct from Hold in that Hold keeps the audit; Demote keeps the annotations but drops the audit. Outcome contingent on Phase 2 data; do not pre-judge which of Hold or Demote is more likely.

The thresholds are guides, not bright lines. Phase 3 may land in the gap between Delete and Shrink and commit a judgement call with rationale; the gap is narrow because the signal axis already does the discrimination the cardinality axis was approximating. The gap between Shrink/Delete and Hold/Demote is wider: it asks whether *any* Phase 4 action earns its keep, and lands on Hold or Demote when the answer is no.

Commit the chosen direction as a Spec → Spec revision with the rationale and the decision-rule application against the actual counts. Under Hold, this commit closes R237 (Phase 4 and Phase 5 do not run). Under Delete, Shrink, or Demote, the commit gates Phase 4 below.

### Phase 4: Implementation

Runs only if Phase 3 selected Delete, Shrink, or Demote. Under Hold, Phase 4 is a no-op and R237 closes at the Phase 3 commit.

Sequence (Delete and Shrink branches):

1. **(a) keys.** For each, delete the `@LoadBearingClassifierCheck` and every paired `@DependsOnClassifierCheck` site. No other change.
2. **(b-cheap) and (b-relational) keys.** Delete the annotations on the same terms as (a). The type-system lift ships under its own follow-up Backlog item, not here. The pre-lift signature is fine; once the lift lands, the contract is mechanically enforced.
3. **(c) keys with empty silent-regression cell** (outside `|c-signal|`): drop the annotations regardless of Delete vs Shrink. These were documentation-of-coincidence, not signal-bearing.
4. **(c) keys in `|c-signal|`** under **Delete**: drop the annotations. For each, the test-side replacement named in Phase 3 (producer-module test exercising the consumer's read pattern, or sibling-module test loading the producer output) lands in the same commit. The cross-module documentation link is lost; the contract migrates from documentation to test code.
5. **(c) keys in `|c-signal|`** under **Shrink**: rename to the cross-module-signal form. Update the audit test's scope.
6. **Audit + annotation infrastructure.**
   - Under **Delete**: remove `LoadBearingClassifierCheck.java`, `LoadBearingClassifierChecks.java`, `DependsOnClassifierCheck.java`, `DependsOnClassifierChecks.java`, `LoadBearingGuaranteeAuditTest.java`, and the `auditfixture/` package.
   - Under **Shrink**: keep the renamed annotation classes; trim `LoadBearingGuaranteeAuditTest` to the `|c-signal|` scope; remove `auditfixture` if no longer needed.

Sequence (Demote branch):

If Phase 3 selected Demote, the annotation sites stay. The audit infrastructure goes (`LoadBearingGuaranteeAuditTest.java`, `auditfixture/`). The annotation classes survive but lose the "load-bearing" framing — rename to `@ClassifierGuarantee` / `@DependsOnClassifierGuarantee` to make the no-audit role explicit, and update each `description` / `reliesOn` field to drop "load-bearing" language. New sites can still adopt the pair as documentation pointers; the audit no longer enforces pairing.

### Replacement convention for producer-consumer linkage records

Applies under Delete and (for any retired sites) Demote.

Once the annotation pair retires, contributors who *do* want to record a producer-consumer dependency (because the link is non-obvious and the type-system narrowing isn't viable for that key) should use javadoc `{@link}` from the consumer to the producer, plus an optional reciprocal `{@link}` on the producer side. No new annotation, no new audit, no new convention to learn. `{@link}` is IDE-refactor-tracked (renaming the producer auto-updates the consumer's reference, unlike the annotation pair's opaque `key` string), carries no prose burden beyond the link itself (sidestepping the false-invariant risk the broadened "Documentation names only live tests/code" principle covers), and reuses the doc-tool the codebase already has. The recommendation does not extend to "every retired site gains a `{@link}`": most retired sites lose the annotation and gain nothing, because the linkage was documentation-of-coincidence rather than load-bearing visibility. `{@link}` is for the residual cases where a future reader, opening the consumer site cold, genuinely needs the pointer to make sense of the code.

Phase 4 done means:
- **Delete / Shrink:** all annotation sites in (a), (b-cheap), (b-relational), and (c \ c-signal) are gone. All `|c-signal|` sites match the chosen direction, with test-side replacements in place under Delete. Annotation classes either removed (Delete) or renamed and scope-restricted (Shrink).
- **Demote:** annotation classes renamed (`@ClassifierGuarantee` / `@DependsOnClassifierGuarantee`), `description` / `reliesOn` fields updated to drop "load-bearing" framing, audit infrastructure removed. All existing site sources still compile.
- Build green in all branches: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` on Java 25.
- Pipeline-tier tests still pin SDL → TypeSpec shape end-to-end (no regression).
- `graphitron-sakila-example` compile still passes (the downstream backstop is still doing its job).

### Phase 5: Knock-on edits

Runs under Delete, Shrink, and Demote (lands together with Phase 4 or as a tail commit). Under Hold, Phase 5 is a no-op — the existing roadmap-item references and test javadocs are consistent with the preserved annotation sites and don't need rephrasing.

- 15 roadmap items naming the pair (R125 and R186 already resolved in Phase 1): rephrase each reference to the new vocabulary (type-system contract, pipeline-test pinning, or cross-module convention as appropriate). Under Demote, also update `description` / `reliesOn` text and any annotation-referencing prose that uses the "load-bearing" framing to match the renamed annotation classes. Do not delete the items; preserve the underlying intent. The list:
  - `binding-provenance-on-paramsource-arg.md`, `simplify-update-mutations-drop-value.md`, `record-parent-column-read-helper.md`, `multi-source-input-validation.md`, `catalog-check-constraint-validation.md`, `consolidate-sources-shape-predicates.md`, `inputs-package-internal-use-audit.md`, `dimensional-model-pivot.md`, `consumer-derived-input-tables.md`, `design-doc-implementation-conformance-audit.md`, `list-valued-external-field-multiset.md`, `tenant-routing-and-execution-input.md`, `graphqlschemavisitor-driven-emission.md` (R166: rephrase "non-empty by load-bearing-pair convention" to name the convention without the pair-specific framing), `honor-field-directive-in-payload-construction-shape.md` (R201: rephrase the two "load-bearing-classifier-check at `:498-505`" cites and the "Update the load-bearing-classifier-check text" instruction to the type-system / pipeline-test contract the emitter relies on), `retire-connection-name-override.md` (R208: rephrase "promote the assumption to a load-bearing classifier check" as "promote the assumption to a validator-enforced invariant", since Phase 1 retires the pattern as an aspirational target).
- Out of scope: `parent-context-aware-schema-coordinates.md` and `intellij-lsp-plugin.md` use the English phrase "load-bearing" descriptively ("Step 2 is the load-bearing change"; "transport is deliberate and load-bearing for the dev loop") with no reference to the annotation classes. The rephrase pass leaves both untouched. Listed here so the next reviewer doesn't expect them in scope.
- Test javadocs in `PkResolutionEmitterReachabilityTest`, `DmlBulkMutationsExecutionTest`, `ServiceFieldValidationTest`, `TableMethodFieldValidationTest`, `LoadBearingGuaranteeAuditTest` (if it survives): rephrase to name the type-system contract or the cross-module mechanism instead of the annotation pair.
- `graphitron-rewrite/roadmap/changelog.md`: append a one-line entry naming R237 and the landing commit SHA.

## Classification table

Walked all 59 unique keys across `graphitron-rewrite/graphitron/` and `graphitron-rewrite/graphitron-lsp/` via FQN-aware grep with annotation-context lookback. Counts feeding Phase 3's decision:

- **|a|** = 50 (46 in-module already type-coupled + 4 orphan producers with no consumer; annotation is duplication of the type or hygiene-only)
- **|b-cheap|** = 1
- **|b-relational|** = 1
- **|c|** = 7 (graphitron producer, graphitron-lsp consumer; compile-link does not reach)
- **|c-signal|** = 0 (every (c) regression is caught by an existing LSP-tier test)

Total: 59 keys. Methodology per § "Phase 2: Classification sweep" above.

### Cross-module keys (|c| = 7)

For each, the producer lives in `graphitron-rewrite/graphitron/`; the consumer lives in `graphitron-rewrite/graphitron-lsp/`. The compile-link does NOT reach across modules — a producer-side relaxation that keeps the same TYPE but changes the VALUE (e.g. drops a payload component, widens a Map's keyset, allows a non-canonical URI) goes silently unless an LSP-tier test catches it.

| key | producer | LSP consumer slot | LSP test backstop | signal-bearing |
|-----|----------|-------------------|-------------------|----------------|
| `field-classification-payload-faithful` | `catalog/CatalogBuilder.java:114` | `FieldClassification` (5 LSP sites read tableName/columnName/joinPath off projection) | `FieldCompletionsTest.columnNameCompletionReturnsTableColumns`, `HoversTest.fieldHoverShowsColumnMetadata`, `DiagnosticsTest.unknownColumnNameProducesError` | no |
| `java-record-type-backs-record-class` | `catalog/CatalogBuilder.java:106` | `RecordBacking.components` (consumed verbatim by FieldCompletions, Hovers, Diagnostics) | `HoversTest.fieldHoverShowsRecordComponentMetadata`, `FieldCompletionsTest`, `DiagnosticsTest` | no |
| `snapshot-built-implies-clean-parse` | `catalog/CatalogBuilder.java:80` | `LspSchemaSnapshot.Built` (silences unknown-directive warns under Previous/Unavailable) | `DiagnosticsTest`, `BuildTriggerPublishesDiagnosticsTest` | no |
| `snapshot-directive-roundtrip-faithful` | `catalog/CatalogBuilder.java:86` | `DirectiveShape.args/description` (consumed verbatim by hover, arg-validation, unknown-directive arm) | `NestedArgsTest`, `ArgNameCompletionsTest`, `HoversTest` | no |
| `source-location.absolute-path-source-name` | `schema/RewriteSchemaLoader.java:64` | `SourceLocation.sourceName` (LSP transforms to canonical URI for diagnostic routing) | `ValidatorDiagnosticsTest` | no |
| `type-classification-payload-faithful` | `catalog/CatalogBuilder.java:130` | `TypeClassification` (5 LSP sites read tableName/participants/fqClassName) | `ReferenceCompletionsTest.keyCompletionReturnsForeignKeysOfEnclosingTable`, `DefinitionsTest.tableDefinitionMapsToTableSourceUri`, `DeclarationHoversTest.cursorOnTypeNameProducesTypeClassificationHover` | no |
| `validation-report.canonical-uri` | `ValidationReport.java:69` | `ValidationReport.sourceUris` Map (URI canonicalization shared between producer and consumer) | `ValidatorDiagnosticsTest.authorErrorMapsToErrorSeverityWithValidatorSource` | no |

All 7 (c) pairs are non-signal-bearing: the LSP-tier tests assert end-to-end behaviour (completion text, hover content, diagnostic URI matching) that depends on the producer's output shape, so a producer relaxation that breaks the LSP consumer fails those tests at LSP-tier. Under R237's broadened "Documentation names only live tests/code" principle, the LSP test suite is the mechanical pin; the annotation is duplication.

### In-module keys (|a| = 50, |b-cheap| = 1, |b-relational| = 1)

File paths stripped of the common prefix `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/`. Producer is `@LoadBearingClassifierCheck` site; consumer is the representative `@DependsOnClassifierCheck` site (full list in the inventory).

| # | key | producer | consumer | bucket | rationale / lift |
|---|-----|----------|----------|--------|------------------|
| 1 | `accessor-rowkey-cardinality-matches-field` | `FieldBuilder.java:4309` | `generators/TypeFetcherGenerator.java:4548` | (a) | `SourceKey.Cardinality` sealed enum (ONE/MANY); consumer pattern-matches on enum value. |
| 2 | `accessor-rowkey-shape-resolved` | `FieldBuilder.java:4296` | `generators/GeneratorUtils.java:270` | (a) | `AccessorRef` record carries resolved method handle + element class from reflection; consumer reads fields directly. |
| 3 | `accessor-rowkey-shape-resolved-against-hub` | `FieldBuilder.java:4659` | (none) | (a) | Orphan producer; hygiene-only check. Drops without replacement under Delete. |
| 4 | `body-param.nonnull-is-effective-runtime` | `FieldBuilder.java:1562` | `generators/TypeConditionsGenerator.java:85` | (a) | `BodyParam.nonNull` boolean carries effective nullability; pipeline-tier coverage on nested-input pins behaviour end-to-end. |
| 5 | `class-accessor-resolver-shape-guarantee` | `ClassAccessorResolver.java:84` | `generators/FetcherEmitter.java:783` | (a) | `AccessorResolution.Resolved` sealed; rejections routed away upstream; consumer sees Resolved type-exclusively. |
| 6 | `column-field-requires-table-backed-parent` | `FieldBuilder.java:4797` | `generators/TypeFetcherGenerator.java:319` | **(b-cheap)** | `parentTable` is currently a parameter into `TypeFetcherGenerator.generateTypeSpec`, not a `ColumnField` record component. *Lift*: add non-null `parentTable` component to `ColumnField` at construction, populated by classifier. Eliminates the parameter threading and the `IllegalStateException` reachability arm in the switch. |
| 7 | `column-reference-field-no-nodeid-encode-keys` | `GraphitronSchemaValidator.java:525` | `generators/InlineColumnReferenceFieldEmitter.java:47` | (a) | Validator rejects upstream; compaction is `Direct`-only on `ColumnReferenceField` before reaching emitters. |
| 8 | `condition-join.target-table-resolved-at-parse` | `BuildContext.java:1467` | `generators/InlineTableFieldEmitter.java:57` | (a) | `ConditionJoin` record's compact constructor enforces non-null `targetTable`; emitters read `cj.targetTable()` without null check. |
| 9 | `context-argument.type-agreement` | `ContextArgumentClassifier.java:50` | `generators/ArgCallEmitter.java:186`, `generators/schema/GraphitronFacadeGenerator.java:43` | (a) | `ResolvedContextArg.javaType` holds single `TypeName` per name; both emitters read the field directly. |
| 10 | `dml-mutation-shape-guarantees` | `FieldBuilder.java:2750` | `generators/TypeFetcherGenerator.java:1684` | (a) | `DmlReturnExpression` sealed with four arms (EncodedSingle/List, ProjectedSingle/List); consumer pattern-matches. |
| 11 | `error-channel.local-context-transport` | `FieldBuilder.java:2881` | `generators/FetcherEmitter.java:53` (5 sites) | (a) | `ErrorChannel` sealed (Local/Global/NoChannel); consumers dispatch on variant. All consumers in-module. |
| 12 | `error-channel.mappings-constant` | `FieldBuilder.java:2125` | `generators/schema/ErrorMappingsClassGenerator.java:48` | (a) | `ErrorChannel.mappingsConstantName` non-null string field; consumer reads it directly. |
| 13 | `error-type.path-message-fields` | `TypeBuilder.java:948` | (none) | (a) | Orphan producer; hygiene-only check on `@error` type shape. |
| 14 | `fetcher-registrations.no-empty-bodies` | `generators/schema/FetcherRegistrationsEmitter.java:62` | `generators/schema/GraphitronSchemaClassGenerator.java:76` | (a) | Map.values() gated at `ifPresent` sites in emitter; type encoding precludes empty entries. |
| 15 | `fk-join.slots-oriented-source-and-target` | `model/JoinStep.java:110` | `FieldBuilder.java:4111` (9 sites) | (a) | `JoinStep.FkJoin` sealed variant; consumers pattern-match on `FkJoin` vs `LiftedHop` exhaustively. |
| 16 | `input-field.unbound-implies-no-column` | `BuildContext.java:1724` | `FieldBuilder.java:1578` | (a) | `InputField.UnboundField` sealed variant; consumer pattern-matches without column lookup. |
| 17 | `input-field.unbound-with-override-condition-admits-on-mutation-update-delete` | `MutationInputResolver.java:313` | (none) | (a) | Orphan producer; documents admission rule but no downstream code branches on it. |
| 18 | `input-record.shape-from-input-type` | `TypeBuilder.java:1146` | `generators/schema/InputRecordGenerator.java:55` | (a) | `InputRecordShape.components` non-empty (compact-constructor enforced); consumers read without null guard. |
| 19 | `lookup-field-non-empty-args` | `FieldBuilder.java:1245` | `generators/LookupValuesJoinEmitter.java:193` | (a) | Sealed variant constructed only when args non-empty; emitter's `requireSlots()` consumes non-empty result. |
| 20 | `lookup-key-input-field-non-list` | `EnumMappingResolver.java:217` | `generators/LookupValuesJoinEmitter.java:114` | (a) | Resolver rejects list-typed carriers before constructing the binding; sealed type permits only scalar. |
| 21 | `lookup-mapping-bindings-table-coherent` | `EnumMappingResolver.java:211` | `generators/LookupValuesJoinEmitter.java:109` | (a) | Bindings resolved against single `inputTable`; binding records carry resolved `targetColumn`. |
| 22 | `multitable-polymorphic-child.parent-key-extraction-is-batchkey-driven-record-parent` | `FieldBuilder.java:4495` | `generators/MultiTablePolymorphicEmitter.java:772` | (a) | `ChildField.InterfaceField`/`UnionField` sealed variants carry resolved `parentSourceKey` + `parentResultType`. |
| 23 | `multitable-polymorphic-child.parent-key-extraction-is-batchkey-driven-table-backed` | `FieldBuilder.java:679` | `generators/MultiTablePolymorphicEmitter.java:765` | (a) | Same sealed-variant pattern; consumer dispatches on variant. |
| 24 | `mutation-delete-carrier.pk-resolution-projection-clean` | `BuildContext.java:507` | `generators/FetcherEmitter.java:715` | (a) | `BuildContext.classifyDeleteTableProjection` rejects non-PK/ServiceField arms; emitter pattern-matches on sealed `PkResolution`. |
| 25 | `mutation-dml-record-field.data-table-equals-input-table` | `FieldBuilder.java:2787` | `generators/FetcherEmitter.java:246` | (a) | Rejection at classification ensures DML carrier binds to input table; emitter reads single `TableRef`. |
| 26 | `mutation-input.lookup-binding-decoded-record-arity-matches-carrier-columns` | `EnumMappingResolver.java:234` | `generators/TypeFetcherGenerator.java:1823` | (a) | `MapBinding` carries arity directly (validated at classification); emitter reads without re-check. |
| 27 | `mutation-input.lookup-binding-honors-carrier-extraction` | `EnumMappingResolver.java:223` | `generators/TypeFetcherGenerator.java:2635` | (a) | `MapBinding` sealed variant (Direct/NodeIdDecodeKeys) carries `extraction`; emitter dispatches on variant. |
| 28 | `mutation-input.update-set-fields-equal-value-marked` | `MutationInputResolver.java:333` | `generators/TypeFetcherGenerator.java:2245` | (a) | `MutationInputTable.setFields` sealed-variant slot (exact partition per DML kind); emitter reads list. |
| 29 | `mutation-input.where-columns-cover-pk` | `MutationInputResolver.java:323` | `generators/TypeFetcherGenerator.java:2659` | (a) | Resolver validates PK coverage; sealed-variant constraint enforces ≤1 row per input row. |
| 30 | `nodeid-fk.direct-fk-keys-match` | `NodeIdLeafResolver.java:231` | `FieldBuilder.java:1340` | (a) | `NodeIdLeafResolver.Resolved.FkTarget` sealed (DirectFk/TranslatedFk); `liftedSourceColumns` pre-permuted into `NodeType.keyColumns` order. |
| 31 | `nodeid-fk.identity-carrying-lift` | `NodeIdLeafResolver.java:245` | `BuildContext.java:1746` | (a) | Resolver validates lift predicate; sealed variant statically encodes valid lift. |
| 32 | `output-fields.uniform-domain-return-type` | `GraphitronSchemaBuilder.java:311` | `generators/FetcherEmitter.java:269` | (a) | Schema builder rejects domain-type disagreement before construction; emitters read single `DomainReturnType` per `(SDL parent, field)`. |
| 33 | `payload-construction.setter-name-matches-sdl-field` | `FieldBuilder.java:499` (stacked) | `generators/TypeFetcherGenerator.java:1617` | (a) | `SetterBinding` sealed variant carries setter `Method` handle (name validated at construction); emitters call `setter.getName()` directly. |
| 34 | `payload-construction.shape-resolved` | `FieldBuilder.java:493` | `generators/TypeFetcherGenerator.java:1603` | (a) | `ErrorChannel.PayloadConstructionShape` sealed (AllFieldsCtor/MutableBean); emitter pattern-matches. |
| 35 | `record-binding.producer-agreement` | `RecordBindingResolver.java:70` | `FieldBuilder.java:3968` | (a) | Resolver returns single `Class<?>`; consumers read class directly. |
| 36 | `scalar-resolver.coercing-non-erased` | `ScalarTypeResolver.java:61` | `ServiceCatalog.java:1243` (5 sites) | (a) | `ScalarResolution.Resolved.javaType` never `Object` (erased coercings route to Rejected); sealed type precludes Object. |
| 37 | `scalar-resolver.javatype-is-typename` | `ScalarTypeResolver.java:68` | `ServiceCatalog.java:1237` (5 sites) | (a) | `ScalarResolution.Resolved.javaType` is typed `TypeName` (JavaPoet); duplicated by the signature. |
| 38 | `service-catalog-instance-service-holder-shape` | `ServiceCatalog.java:170` | `generators/TypeFetcherGenerator.java:1399` | (a) | `Service.callShape` sealed (InstanceWithDslHolder/Static); structurally encodes holder shape. |
| 39 | `service-catalog-strict-service-return` | `ServiceCatalog.java:159` | `generators/TypeFetcherGenerator.java:1246` | (a) | `MethodRef.Service` carries captured parameterised return type; emitters declare typed fetcher return. |
| 40 | `service-catalog-strict-tablemethod-return` | `ServiceCatalog.java:498` | `generators/TypeFetcherGenerator.java:1035`, `1114` | **(b-relational)** | Strict return-type check guarantees field's table token equals method's return token at runtime; neither record carries the relationship type-structurally. *Lift*: parameterise `MethodRef.StaticOnly` and `ReturnTypeRef.TableBoundReturnType` on a shared `<T extends Table<?>>` type token; thread it through every site that constructs or reads either. Higher blast radius than (b-cheap); jOOQ helper boundaries (per § "Selection-aware queries") cap how far the bound carries. |
| 41 | `service-catalog-tablemethod-must-be-static` | `ServiceCatalog.java:504` | `generators/TypeFetcherGenerator.java:1040` | (a) | `MethodRef.StaticOnly` sealed variant; emitter reads without forking. |
| 42 | `service-directive-resolver-strict-child-service-return` | `ServiceDirectiveResolver.java:359` | `generators/TypeFetcherGenerator.java:4335` | (a) | `MethodRef.Param.Sourced` carries validated return type; emitters use it directly. |
| 43 | `service-method.declared-exceptions-covered` | `FieldBuilder.java:2653` | (none) | (a) | Orphan producer; checks declared-exception coverage but no consumer branches on it. |
| 44 | `service-resolver-root-list-record-return-pair` | `ServiceDirectiveResolver.java:274` | `generators/TypeFetcherGenerator.java:1254` | (a) | Sealed return type permits only `Result<XRecord>` or `List<XRecord>` shapes. |
| 45 | `source-key.accessor-call-wraps-record` | `model/SourceKey.java:84` | `generators/SplitRowsMethodEmitter.java:157` | (a) | `SourceKey.Reader.AccessorCall` ⇒ `Wrap.Record` invariant pinned by compact constructor. |
| 46 | `source-key.result-row-walk-target-aligned-empty-path` | `model/SourceKey.java:96` | `generators/FetcherEmitter.java:256` | (a) | Compact-constructor invariant on `Reader.ResultRowWalk` wrap + empty path. |
| 47 | `source-key.service-table-record-target-aligned-empty-path` | `model/SourceKey.java:90` | `generators/GeneratorUtils.java:418` | (a) | Compact-constructor invariant on `Reader.ServiceTableRecord` recordType + empty path. |
| 48 | `source-key.source-rows-call-wraps-row` | `model/SourceKey.java:78` | `generators/GeneratorUtils.java:252` | (a) | Compact-constructor invariant on `Reader.SourceRowsCall` ⇒ `Wrap.Row`. |
| 49 | `sourcerow-classifies-as-record-table-field` | `SourceRowDirectiveResolver.java:115` | `generators/SplitRowsMethodEmitter.java:148` | (a) | Resolver routes `@sourceRow` to `RecordTableField`/`RecordLookupTableField` sealed variants only. |
| 50 | `sourcerow-leafkey-sourcerows-singlehop` | `SourceRowDirectiveResolver.java:121` | `generators/GeneratorUtils.java:239` | (a) | `SourceKey` carries single-element `path [JoinStep.LiftedHop]` on leaf-PK arm; sealed path variant. |
| 51 | `sourcerow-pathkey-sourcerows-fkchain` | `SourceRowDirectiveResolver.java:127` | `generators/GeneratorUtils.java:247` | (a) | `SourceKey` carries multi-step `path [JoinStep.FkJoin...]` on `@reference` arm; sealed path variant. |
| 52 | `tablemethod-resolver-return-is-table-bound` | `TableMethodDirectiveResolver.java:92` | `model/ChildField.java:434`, `model/QueryField.java:82` | (a) | `ChildField.TableMethodField` / `QueryField.QueryTableMethodTableField` declare `returnType` as `ReturnTypeRef.TableBoundReturnType` (narrow sealed component). |

### Backlog items to file

Two follow-up Backlog items, one per (b-*) key. These ship independently of R237's retirement — the annotation retires under Phase 4 regardless of whether the lift has shipped (the pre-lift signature is fine; once the lift lands, the contract is mechanically enforced):

- **`column-field-requires-table-backed-parent`** (b-cheap): Lift `parentTable` from a `TypeFetcherGenerator.generateTypeSpec` parameter to a non-null `ColumnField` record component, populated at construction in `BuildContext.classifyOutputField`. Eliminates the parameter threading and the `IllegalStateException` reachability arm at `TypeFetcherGenerator.java:319`.

- **`service-catalog-strict-tablemethod-return`** (b-relational): Thread a shared `<T extends Table<?>>` type token through `MethodRef.StaticOnly` and `ReturnTypeRef.TableBoundReturnType` so the field's table token equals the method's return token structurally. Higher blast radius than (b-cheap); jOOQ helper boundaries cap how far the bound carries. The strict-`@service` return-type sibling (`service-catalog-strict-service-return`) is currently (a) and benefits as a side effect.

## Done means

Outcomes that hold in every Phase 3 branch (these are what Phase 1 delivers):
- The principles doc no longer endorses the annotation pair as the enforcement layer; the principle anchors on type signatures, pipeline tests, and the sakila-compile backstop.
- The `principles-architect` agent and the reviewer/SRP skill rubrics no longer push the pattern.
- The genuine load-bearing safety nets (type signatures, pipeline tests, sakila-compile) remain unchanged and continue to catch the contract failures the retired annotations claimed to catch.
- R125 and R186 resolved; the roadmap no longer carries an item that contradicts the new principle.

Outcomes specific to the Phase 3 branch:
- **Delete:** all in-module annotation sites gone; the 60 string keys gone; audit infrastructure removed; `|c-signal|` pairs replaced by test-side mechanisms.
- **Shrink:** in-module annotation sites gone; the 60 string keys reduced to the `|c-signal|` residual; audit infrastructure restricted to that residual; annotation classes renamed to the cross-module-signal form.
- **Demote:** annotation sites preserved with renamed classes (`@ClassifierGuarantee` / `@DependsOnClassifierGuarantee`); audit infrastructure removed; annotations function as IDE-navigable documentation pointers without build-time enforcement.
- **Hold:** annotation sites and audit infrastructure preserved as archaeology. R237's gain is the Phase 1 outcomes above; the existing site cost is accepted in exchange for not churning 75 sites.

## Out of scope

- The per-(b-cheap) / per-(b-relational) type-system lifts. Each lift is its own Backlog item, filed during Phase 2 and shipped under its own ID. R237's implementation here is the annotation retirement and the principles revision, not the lifts themselves.
- R207 (the meta-audit of design-doc claims for implementation conformance). R207 is the general question; R237 closes one specific instance, which sharpens R207's framing but does not subsume it.
- The legacy `graphitron-codegen-parent` / `graphitron-common` / `graphitron-servlet-parent` modules. None of the annotations live there per CLAUDE.md scope; no edits to those modules are required.
- Rewriting historical changelog entries. Under Delete, `graphitron-rewrite/roadmap/changelog.md` retains roughly 49 entries that name the retired annotation classes (R21 onward). These are archaeological: each entry describes what shipped on a given date in the form of the convention then in force. The "Documentation names only live tests/code" principle (as broadened in Phase 1) covers *forward-looking invariant claims*, not historical descriptions of code that shipped. Phase 5's changelog entry records R237 and the retirement; prior entries stay as-is.

## Open risks

- **(b-relational) keys that resist a clean lift.** Multi-record type-token threading may be blocked by jOOQ generic erasure, graphitron-javapoet API limits, or sealed-hierarchy ceilings the codebase has already hit. (b-cheap) keys are bounded by definition; if classification reveals one is harder than expected it reclassifies to (b-relational), not blocked. Containment: any (b-relational) key whose lift turns out infeasible during the Phase 2 walk reclassifies to (c) on the spot; the lift Backlog item does not get filed. Phase 3 sees the updated counts; do not block R237 on a stuck lift.
- **Phase 2 cost.** The classification sweep is 60 keys, each requiring producer + consumer inspection. Realistically a single multi-hour session. If it threatens to balloon, split Phase 2 by package and ship the table in chunks via Spec → Spec revisions.
- **Cross-package architect-agent regressions.** Phase 1's edit to `principles-architect.md` swaps in a new family ("missing type-system lift"). The new family may over-trigger on legitimate signature widenings (jOOQ helper boundaries, the graphql-java SDL surface). Containment: tune the agent prompt rather than reverting the principles change; the architect agent's family list is not part of any Done criteria here.
- **In-flight items propagating the pattern** (mitigated in Phase 1). Two items qualify: `loadbearing-classifier-checks-multischema-fqn.md` (R125, `Backlog`) wants to add the pattern at more sites and would contradict R237's direction wholesale; `nested-input-types-in-mutation-fields.md` (R186, `Spec`) proposes a new audit key and paired annotation inside its Phase-5-step-7 section, a single bullet in an otherwise orthogonal item. R237 supersedes both directions. Containment: Phase 1 concrete edit 6 resolves each in the same commit as the principles revision (R125: Discard or rewrite around the type-system equivalent; R186: drop the annotation bullet or reframe to a pipeline-test pin). Listed as a risk for visibility; the closing happens at Phase 1 commit time.
- **Sign-off churn on in-flight items.** R232 just landed with new annotation sites; R220, R218, R219 cite the pattern. Phase 1's principles revision does not invalidate sign-offs because the existing sites still compile and still audit-clean. Phase 5's roadmap-item rephrasing is text-only.
