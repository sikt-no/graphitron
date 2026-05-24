---
id: R237
title: "Retire @LoadBearingClassifierCheck / @DependsOnClassifierCheck annotation pair"
status: Backlog
bucket: architecture
theme: structural-refactor
depends-on: []
created: 2026-05-24
last-updated: 2026-05-24
---

# Retire @LoadBearingClassifierCheck / @DependsOnClassifierCheck annotation pair

R230 introduced the `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` annotation pair plus `LoadBearingGuaranteeAuditTest` to mechanise the principle at `rewrite-design-principles.adoc § "Classifier guarantees shape emitter assumptions"`. The pair spread fast — 75 sites and 60 keys across the rewrite module in two days, with `principles-architect` actively pushing new sites in (R232's "fold in arch-agent findings ... load-bearing annotation" is one example). The pattern does not carry the weight its name claims, and its current trajectory institutionalises a documentation drift mode another principle already condemns.

The audit catches three drift modes: orphaned consumer (producer renamed/deleted), duplicate producer, and blank `description`/`reliesOn`. The most consequential drift mode — a producer's check is silently relaxed (e.g. `ClassName.equals` widened to `startsWith`, an `instanceof` arm broadened) — is invisible: the key string is still present, the prose description still claims the original guarantee, the audit passes, and the contract is now broken in a way that only surfaces when the generated source fails to compile against `graphitron-sakila-example`. The audit's actual teeth reduce to "did the producer get renamed?", which `git grep` over the key answers equally well.

The verbose `description` / `reliesOn` prose (typically 4–8 lines per site) is exactly the kind of trusted invariant prose that `rewrite-design-principles.adoc § "Documentation names only live tests/code"` warns against. Readers trust the description; nothing pins the description to the code. The pair institutionalises false-invariant drift at scale (60 such descriptions today).

The principle the annotation embodies (classifier acceptance lets the emitter assume narrow shapes) is sound, and the two worked examples in the principles doc (strict `tablemethod` return, `ColumnField` parent table) are real wins. Both are also expressible as type-system contracts: a parameterised `MethodRef<TableBoundReturn>` on the resolver side narrows the typed local at the rewrite-module compile; a non-null `parentTable` component on `ColumnField` makes the `IllegalStateException` reachability arm unreachable. Where the lift has already happened (`ScalarResolution.Resolved#javaType` is already a `TypeName`), the annotation is pure duplication. Where it hasn't, the annotation is a band-aid for a missing type-system lift. Either way, the cross-module compile against `graphitron-sakila-example` and the pipeline-tier tests are doing the genuine load-bearing work; the annotation pair is a documentation convention dressed up as a build-time check.

## Spec-stage prompts

The Spec phase picks one of the positions below and defends the choice. None of these have an obvious answer; the choice depends on the (a)/(b)/(c) classification the Spec sweeps over the 60 existing keys.

- **Delete entirely.** Remove `LoadBearingClassifierCheck`, `DependsOnClassifierCheck`, the `*Checks` repeatable containers, `LoadBearingGuaranteeAuditTest`, and the `auditfixture` package. Revise `rewrite-design-principles.adoc § "Classifier guarantees shape emitter assumptions"` to re-anchor on type-system + pipeline-test enforcement, keeping the two worked examples reframed as type-system contracts. Drop "load-bearing classifier checks" from `.claude/agents/principles-architect.md`'s high-leverage families list and the `.claude/skills/reviewer-prompt/SKILL.md` / `.claude/skills/srp/SKILL.md` references.
- **Shrink to cross-module.** Keep the pair for producer/consumer relationships where the type system cannot reach — generator ↔ LSP and generator ↔ `graphitron-sakila-service`, both of which sit outside the rewrite module's compile boundary. Within `graphitron-rewrite/graphitron/`, delete the annotations as the type-system lift lands. The principles doc is revised to name the cross-module gap explicitly.
- **Demote to documentation only.** Strip the audit test; keep the annotations as inert prose docstrings purely for find-usages navigation. Cheapest revision but leaves the false-invariant drift mode in place.

## Classification sweep (precondition for the Spec choice)

Before the Spec decides between Delete / Shrink / Demote, run a one-pass classification over each of the 60 keys (currently visible via `grep -rn "@LoadBearingClassifierCheck" --include="*.java"`):

- **(a) Already type-coupled.** The producer's return type / record component / parameter type already carries the narrowness the description claims. The annotation is pure duplication; delete with no other change. Candidate example: `scalar-resolver.javatype-is-typename` (`ScalarResolution.Resolved#javaType` is already typed `TypeName`).
- **(b) Liftable to the type system.** A narrower producer return type or sub-variant record would carry the contract structurally. File a follow-up Backlog item per lift, then delete the annotation. Candidate examples: `service-catalog-strict-tablemethod-return`, `service-resolver-root-list-record-return-pair`, `service-directive-resolver-strict-child-service-return` (all narrowable via parameterised `MethodRef` / a sealed `ReturnTypeShape`); `column-field-requires-table-backed-parent` (move to a non-null `ColumnField#parentTable` component or a sealed sub-variant).
- **(c) Cross-module without a compile dependency.** Producer in the generator, consumer in `graphitron-lsp` or `graphitron-sakila-service`; the type system cannot enforce the link because the consumer module has no compile-time dependency on the producer. Candidate examples: every key consumed under `graphitron-rewrite/graphitron-lsp/` (`field-classification-payload-faithful`, `type-classification-payload-faithful`, `java-record-type-backs-record-class`, ...) and the `service-catalog-instance-service-holder-shape` pair against `graphitron-sakila-service`. The Shrink option exists for this set.

The classification is itself the work; the implementation flows from it. If (a)+(b) dominate, Delete is correct. If (c) is substantial, Shrink is the conservative middle.

## Knock-on edits

Whichever option the Spec picks, these touchpoints carry references that need to move with the principle:

- `graphitron-rewrite/docs/rewrite-design-principles.adoc` § "Classifier guarantees shape emitter assumptions" (the home rule).
- `graphitron-rewrite/docs/dispatch-axes.adoc` and `graphitron-rewrite/docs/README.adoc` (cite the pattern).
- `.claude/agents/principles-architect.md` (line 14, 28, 45; the agent actively pushes the pattern).
- `.claude/skills/reviewer-prompt/SKILL.md`, `.claude/skills/srp/SKILL.md` (cite the pattern in reviewer/SRP rubrics).
- 15 roadmap items reference the annotation by name. Rephrase to the type-system or pipeline-test mechanism the principle now anchors on; do not delete the items.
- The `PkResolutionEmitterReachabilityTest` and `DmlBulkMutationsExecutionTest` javadocs that name the pair as the enforcement layer for specific keys.

## Out of scope

- The per-key type-system lifts in category (b) above. Each lift gets its own Backlog item filed during this item's Spec phase; the implementation here is the annotation retirement + principles revision, not the lifts.
- The general "audit design-doc claims for implementation conformance" effort (R207). R207 is the meta-question; R237 closes one specific instance.
