---
id: R237
title: Retire @LoadBearingClassifierCheck / @DependsOnClassifierCheck annotation pair
status: Spec
bucket: architecture
theme: structural-refactor
depends-on: []
created: 2026-05-24
last-updated: 2026-05-24
---

# Retire @LoadBearingClassifierCheck / @DependsOnClassifierCheck annotation pair

## Problem

R230 introduced the `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` annotation pair plus `LoadBearingGuaranteeAuditTest` to mechanise the principle at `rewrite-design-principles.adoc § "Classifier guarantees shape emitter assumptions"`. The pair spread fast — 75 sites and 60 keys across the rewrite module in two days, with `principles-architect` actively pushing new sites in (R232's "fold in arch-agent findings ... load-bearing annotation" is one example). The pattern does not carry the weight its name claims, and its current trajectory institutionalises a documentation drift mode another principle already condemns.

The audit catches three drift modes: orphaned consumer (producer renamed/deleted), duplicate producer, and blank `description`/`reliesOn`. The most consequential drift mode — a producer's check is silently relaxed (e.g. `ClassName.equals` widened to `startsWith`, an `instanceof` arm broadened) — is invisible: the key string is still present, the prose description still claims the original guarantee, the audit passes, and the contract is now broken in a way that only surfaces when the generated source fails to compile against `graphitron-sakila-example`. The audit's actual teeth reduce to "did the producer get renamed?", which `git grep` over the key answers equally well.

The verbose `description` / `reliesOn` prose (typically 4–8 lines per site) is exactly the kind of trusted invariant prose that `rewrite-design-principles.adoc § "Documentation names only live tests/code"` warns against. Readers trust the description; nothing pins the description to the code. The pair institutionalises false-invariant drift at scale (60 such descriptions today).

The principle the annotation embodies (classifier acceptance lets the emitter assume narrow shapes) is sound, and the two worked examples in the principles doc (strict `tablemethod` return, `ColumnField` parent table) are real wins. Both are also expressible as type-system contracts: a parameterised `MethodRef<TableBoundReturn>` on the resolver side narrows the typed local at the rewrite-module compile; a non-null `parentTable` component on `ColumnField` makes the `IllegalStateException` reachability arm unreachable. Where the lift has already happened (`ScalarResolution.Resolved#javaType` is already a `TypeName`), the annotation is pure duplication. Where it hasn't, the annotation is a band-aid for a missing type-system lift. Either way, the cross-module compile against `graphitron-sakila-example` and the pipeline-tier tests are doing the genuine load-bearing work; the annotation pair is a documentation convention dressed up as a build-time check.

## What we believe is true

The trace that the annotation pair is the wrong enforcement layer rests on five claims, each independently checkable against the codebase as it stands today.

1. **The underlying principle is sound.** Classifier acceptance does let an emitter assume narrower shapes; that's why both worked examples in the principles doc (strict `tablemethod` return, `ColumnField` parent table) catch real bugs. The principle stays. Only the enforcement layer changes.
2. **The annotation's audit teeth are limited to rename / delete of the producer.** `LoadBearingGuaranteeAuditTest` (`graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/model/LoadBearingGuaranteeAuditTest.java:82-113`) groups by `key` and flags orphaned consumers, duplicate producers, and blank prose fields. Nothing else. A silent relaxation of the producer's check body (widening a `ClassName.equals` to a `startsWith`, broadening an `instanceof` arm) passes the audit cleanly so long as the annotation's `key` stays in place.
3. **The genuine safety nets sit downstream.** `rewrite-design-principles.adoc:111` says so directly: the safety net is `mvn compile -pl :graphitron-sakila-example`. The annotation pair is "the cheap upstream version of the same signal." Inspection: the downstream check catches silent relaxation; the upstream audit does not. The cheap upstream signal is weaker than the expensive downstream one, not stronger.
4. **The principle the annotation embodies is expressible in the type system at many of the existing sites.** Sample inspection:
   - `scalar-resolver.javatype-is-typename` (ScalarTypeResolver.java:68-73): the description says "javaType is a JavaPoet TypeName" — and `ScalarResolution.Resolved#javaType` is already typed `TypeName`. The annotation is pure duplication of the signature.
   - `service-catalog-strict-tablemethod-return`: the description says "the developer's method return type equals the generated jOOQ table class" — expressible by parameterising `MethodRef` on the table type so the emitter's typed local cannot widen at the rewrite-module compile.
   - `column-field-requires-table-backed-parent`: the description says "the classifier only produces a `ColumnField` on a table-backed parent" — expressible by making `ColumnField`'s `parentTable` component non-null at construction, making the `IllegalStateException` reachability arm unreachable.
5. **The verbose `description` / `reliesOn` prose is exactly the failure mode `rewrite-design-principles.adoc § "Documentation names only live tests/code"` warns against.** The current doc states "a javadoc comment saying 'enforced by X' when X does not exist is worse than no comment — it's a false invariant that readers trust." The annotation `description` field claims "the producer rejects X so the emitter may assume Y" — once a producer is silently relaxed, the description becomes a false invariant. At 60 sites today, the pattern institutionalises this failure mode at scale.

The conclusion is that the annotation pair is a documentation convention dressed up as a build-time check. The principle survives; the enforcement layer moves to where it already lives (type signatures, pipeline tests, the sakila-example compile).

## Phased plan

The item ships in five phases. Phase 1 (principles revision) is the smallest reversible step that stops the bleeding without touching any of the 75 existing annotation sites; subsequent phases retire those sites in separate visibility windows. The plan stages this way because the architect agent and reviewer/SRP rubrics actively push the pattern today; the principles edit removes that pressure on day one, so the 60-key classification doesn't grow under us while it runs.

### Phase 1: Principles revision

Lands first, alone. No code touched; no annotation sites removed. The principles doc and the agent/skill rubrics stop endorsing the annotation pair as the enforcement layer; the existing pair stays in place until Phase 4.

Concrete edits:

1. `graphitron-rewrite/docs/rewrite-design-principles.adoc` § "Classifier guarantees shape emitter assumptions" (lines 107–122): rewrite to anchor the principle on (i) type-system narrowing at the producer, (ii) pipeline-tier tests for end-to-end SDL → TypeSpec drift, (iii) the sakila-example compile as the cross-module backstop. Keep the two worked examples (strict `tablemethod` return, `ColumnField` parent table) but reframe each as a candidate type-system lift. Drop the "Enforcement (the contractual form)" paragraph that names `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` as the contractual form. Add a one-sentence note that the annotation pair is in retirement under R237; existing sites remain audit-clean until they retire.
2. `.claude/agents/principles-architect.md`:
   - Line 14 ("places a classifier guarantee should be load-bearing"): rephrase to "places a producer narrows a shape that downstream consumers consume without the narrowed type."
   - Line 28 (the principles-doc citation list): keep the section name in canonical form; drop the annotation reference.
   - Line 45 ("Load-bearing classifier checks. A classifier guarantee an emitter relies on, without the `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` annotation pair."): replace with "Missing type-system lift. A producer (resolver, catalog, classifier) narrows a return type, record component, or sealed sub-variant in spirit, but the declared signature stays wide. The contract belongs in the signature."
3. `.claude/skills/reviewer-prompt/SKILL.md`, `.claude/skills/srp/SKILL.md`: locate the annotation-pair citations (currently in the reviewer/SRP rubrics) and remove. Keep the underlying classifier-shape principle reference under its new framing.
4. `graphitron-rewrite/docs/dispatch-axes.adoc`, `graphitron-rewrite/docs/README.adoc`: edit citations of the pair to read as the principle's underlying form (type-system + tests) rather than the mechanical annotation form.

Out of scope for Phase 1:
- The 75 existing annotation sites stay untouched. `LoadBearingGuaranteeAuditTest` still runs and still passes.
- The 15 roadmap items still reference the pair by name. Phase 5 rephrases them.

Phase 1 done means:
- The Phase 1 commit lands on trunk. `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` green on Java 25. `LoadBearingGuaranteeAuditTest` still green; no annotations changed.
- The architect agent and reviewer/SRP rubrics no longer push the annotation pattern.
- The principles doc points at type-system + tests as the enforcement layer.

### Phase 2: Classification sweep

Walks each of the ~60 unique keys (the live set comes from `grep -hE '^\s*key = "' graphitron-rewrite/graphitron/src/main/java/...` over annotation sites). For each, classify:

- **(a) Already type-coupled.** The producer's return type / record component / parameter type already carries the narrowness the description claims. Annotation is pure duplication.
- **(b) Liftable to the type system.** A narrower producer signature would carry the contract structurally. Each (b) key generates a follow-up Backlog item filed during this phase; the lift ships under that follow-up, not R237.
- **(c) Cross-module without a compile dependency.** Producer in `graphitron-rewrite/graphitron`, consumer in `graphitron-rewrite/graphitron-lsp` or `graphitron-rewrite/graphitron-sakila-service` (or any downstream consumer project). The compile-link cannot reach across the module boundary.

Methodology:
- For each key, list (producer file:line, consumer file:line, current producer return / component type, what the description claims the contract is). One row per key.
- Answer "could the producer's signature carry this?". (a) if it already does; (b) if a narrower signature is feasible without unreasonable cost; (c) if the consumer sits in a module that doesn't compile against the producer.
- Commit the table inline in this Spec file (under `## Classification table`) as a Spec → Spec revision.

Out of scope for Phase 2:
- Executing the per-key lifts. (b) entries file Backlog items; the lifts ship under those items.
- Touching the annotations in code.

Phase 2 done means:
- Classification table committed in this Spec. Counts known: `|a| + |b|` vs `|c|`.
- One Backlog item filed per (b) key (titles seeded by the current annotation's `description`).

### Phase 3: Pick Delete vs Shrink

Decision rule, applied to the Phase 2 counts:

- **Delete.** Pick when `|c| ≤ ~10` (Shrink's residual benefit doesn't justify keeping the API surface, the audit test, and the cross-module documentation convention). Remove the annotation classes, the `*Checks` containers, `LoadBearingGuaranteeAuditTest`, and the `auditfixture` package. The principles doc already names the cross-module gap (per Phase 1) as a known cost the project accepts.
- **Shrink.** Pick when `|c|` is materially larger (`> ~15`) and the cross-module documentation link has demonstrable per-pair value. Rename the annotations to a cross-module-only form (proposed: `@CrossModuleClassifierGuarantee` / `@DependsOnCrossModuleClassifierGuarantee`). Restrict `LoadBearingGuaranteeAuditTest` to scan only the cross-module set. In-module pairs in (a) and (b) still retire.
- **Demote.** Pick only if Phase 2 reveals that nearly every key is (c) AND the lifts in (b) prove infeasible. Listed for completeness; unlikely.

Commit the chosen direction as a Spec → Spec revision with the rationale and the decision-rule application against the actual counts.

### Phase 4: Implementation

Sequence:

1. **(a) keys.** For each, delete the `@LoadBearingClassifierCheck` and every paired `@DependsOnClassifierCheck` site. No other change.
2. **(b) keys.** Delete the annotations on the same terms as (a). The type-system lift ships under its own follow-up Backlog item, not here. The pre-lift signature is fine; once the lift lands, the contract is mechanically enforced.
3. **(c) keys** under **Delete**: drop the annotations. The cross-module documentation link is lost; accepted as cost.
4. **(c) keys** under **Shrink**: rename to the cross-module form. Update the audit test's allowlist.
5. **Audit + annotation infrastructure.**
   - Under **Delete**: remove `LoadBearingClassifierCheck.java`, `LoadBearingClassifierChecks.java`, `DependsOnClassifierCheck.java`, `DependsOnClassifierChecks.java`, `LoadBearingGuaranteeAuditTest.java`, and the `auditfixture/` package.
   - Under **Shrink**: keep the renamed annotation classes; trim `LoadBearingGuaranteeAuditTest` to the cross-module scope; remove `auditfixture` if no longer needed.

Phase 4 done means:
- All annotation sites in (a) and (b) are gone.
- All (c) sites match the chosen direction.
- Annotation classes either removed (Delete) or renamed and scope-restricted (Shrink).
- Build green: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` on Java 25.
- Pipeline-tier tests still pin SDL → TypeSpec shape end-to-end (no regression).
- `graphitron-sakila-example` compile still passes (the downstream backstop is still doing its job).

### Phase 5: Knock-on edits

Lands together with Phase 4 or as a tail commit:

- 15 roadmap items naming the pair: rephrase each reference to the new vocabulary (type-system contract, pipeline-test pinning, or cross-module convention as appropriate). Do not delete the items; preserve the underlying intent. The list:
  - `binding-provenance-on-paramsource-arg.md`, `simplify-update-mutations-drop-value.md`, `record-parent-column-read-helper.md`, `multi-source-input-validation.md`, `catalog-check-constraint-validation.md`, `consolidate-sources-shape-predicates.md`, `loadbearing-classifier-checks-multischema-fqn.md`, `inputs-package-internal-use-audit.md`, `input-model-dimensional-pivot.md`, `consumer-derived-input-tables.md`, `design-doc-implementation-conformance-audit.md`, `list-valued-external-field-multiset.md`, `tenant-routing-and-execution-input.md`, `parent-context-aware-schema-coordinates.md`, `intellij-lsp-plugin.md`.
  - Special case: `loadbearing-classifier-checks-multischema-fqn.md` (R125) is structurally an "add more annotations" item; it must be re-scoped or Discarded under R237's direction. Decide during Phase 5 based on the Phase 3 outcome.
- Test javadocs in `PkResolutionEmitterReachabilityTest`, `DmlBulkMutationsExecutionTest`, `ServiceFieldValidationTest`, `TableMethodFieldValidationTest`, `LoadBearingGuaranteeAuditTest` (if it survives): rephrase to name the type-system contract or the cross-module mechanism instead of the annotation pair.
- `graphitron-rewrite/roadmap/changelog.md`: append a one-line entry naming R237 and the landing commit SHA.

## Done means

- The principles doc no longer endorses the annotation pair as the enforcement layer; the principle anchors on type signatures, pipeline tests, and the sakila-compile backstop.
- The `principles-architect` agent and the reviewer/SRP skill rubrics no longer push the pattern.
- All in-module annotation sites are gone (Delete) or restricted to cross-module pairs (Shrink).
- The 60 string keys are gone (Delete) or reduced to the cross-module residual (Shrink).
- The genuine load-bearing safety nets (type signatures, pipeline tests, sakila-compile) remain unchanged and continue to catch the contract failures the retired annotations claimed to catch.

## Out of scope

- The per-(b)-key type-system lifts. Each lift is its own Backlog item, filed during Phase 2 and shipped under its own ID. R237's implementation here is the annotation retirement and the principles revision, not the lifts themselves.
- R207 (the meta-audit of design-doc claims for implementation conformance). R207 is the general question; R237 closes one specific instance, which sharpens R207's framing but does not subsume it.
- The legacy `graphitron-codegen-parent` / `graphitron-common` / `graphitron-servlet-parent` modules. None of the annotations live there per CLAUDE.md scope; no edits to those modules are required.

## Open risks

- **(b) keys that resist a clean lift.** Some narrowings may be blocked by jOOQ generic erasure, graphitron-javapoet API limits, or sealed-hierarchy ceilings the codebase has already hit. Containment: any (b) key whose lift turns out infeasible during the Phase 2 walk reclassifies to (c) on the spot; the lift Backlog item does not get filed. Phase 3 sees the updated counts; do not block R237 on a stuck lift.
- **Phase 2 cost.** The classification sweep is 60 keys, each requiring producer + consumer inspection. Realistically a single multi-hour session. If it threatens to balloon, split Phase 2 by package and ship the table in chunks via Spec → Spec revisions.
- **Cross-package architect-agent regressions.** Phase 1's edit to `principles-architect.md` swaps in a new family ("missing type-system lift"). The new family may over-trigger on legitimate signature widenings (jOOQ helper boundaries, the graphql-java SDL surface). Containment: tune the agent prompt rather than reverting the principles change; the architect agent's family list is not part of any Done criteria here.
- **R125 collision.** `loadbearing-classifier-checks-multischema-fqn.md` (R125) wants to add the pattern at more sites and is in Backlog. R237 supersedes its direction. Containment: Phase 5 either rewrites R125's body around the type-system equivalent or Discards R125 outright. The decision is small and local; flag it during Phase 5.
- **Sign-off churn on in-flight items.** R232 just landed with new annotation sites; R220, R218, R219 cite the pattern. Phase 1's principles revision does not invalidate sign-offs because the existing sites still compile and still audit-clean. Phase 5's roadmap-item rephrasing is text-only.
