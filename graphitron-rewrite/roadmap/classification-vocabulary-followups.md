---
id: R3
title: Classification vocabulary follow-ups
status: Spec
priority: 5
theme: docs
depends-on: []
---

# Plan: Classification Vocabulary Follow-ups

> Seven independent doc/generator-behaviour cleanups surfaced during the `code-generation-triggers.md` rewrite plus G5's Pending Review sweep; none picked up yet. Any of them can land independently; none is a release blocker.

Each item below corrects a place where the doc or the code still treats `@lookupKey` as scope-defining, mis-states the `@condition` rule, or is missing the new **source context vs. target type** split. Items are prioritised rough-to-low effort.

**Claim verification (2026-04-17)**: all item claims below were re-verified against the branch after the sealed-switch work landed. Source line numbers in the original draft had drifted ~20–30 lines; this rewrite uses identifier-level references instead so the items don't go stale with every refactor.

---

## 1. ~~G6 table — `@condition` is not blocked on lookup fields~~ *(Done)*

Addressed during the roadmap deep-clean: the G6 table was moved from the roadmap to
[code-generation-triggers.md](../code-generation-triggers.md#dataloader-backed-field-categories)
and built with the correct values — `@condition` column now reads
"Allowed — must preserve N × M contract†" on all three lookup rows, with a footnote pointing
to the [Derived tables](../code-generation-triggers.md#derived-tables) contract definition.

---

## 2. Emit a build warning for `@splitQuery` on a result-mapped parent

`FieldBuilder.classifyChildFieldOnResultType` does not read `@splitQuery` at all. On a `@record`
parent the directive is silently ignored because the record handoff already opens a new
DataLoader-backed scope — the split is redundant.

[code-generation-triggers.md](../code-generation-triggers.md) already states this should be a
**warning** (not an error) — the doc is ahead of the code.

Silent acceptance is a trap: a developer adding `@splitQuery` to "make batching kick in" has
no way to discover it was a no-op.

**Change.**
- Introduce a warnings channel on the builder. Today there is none — only `LOG.warn` for the
  one-off `ExternalCodeReference 'name' is deprecated` case. Shape suggestions:
  - A `List<BuildWarning> warnings()` on `GraphitronSchema` (parallel to `errors()`), or
  - An additional `warnings` out-parameter on the builder method family.
  Pick the minimal shape the maven plugin can surface at build time without changing its public
  contract.
- In `classifyChildFieldOnResultType`, emit a warning when `@splitQuery` is present. Keep
  producing `RecordTableField` / `RecordLookupTableField` as today; the directive is purely
  informational on this source context.
- Pipeline test: add a case with a `@record` parent and a `@table`-returning child carrying
  `@splitQuery`. Assert (a) classification still succeeds, (b) a warning is reported through
  the new channel, (c) the field variant is still `RecordTableField` / `RecordLookupTableField`.

**Reusability.** The warnings channel landed with the `@table + @record` input-type fix —
warnings flow through `BuildContext.addWarning(BuildWarning)` and surface via
`GraphitronSchema.warnings()` to `GraphQLRewriteGenerator` / `ValidateMojo`. This item
adds a second caller on the same channel. It will also serve P2 #3 ("validator asks
can-this-generate") if we want `NOT_IMPLEMENTED_REASONS` hits to be warnings rather than
errors in some configurations.

---

## 3. Audit other docs for lookup-in-scope and condition-blocked wording

Systematic audit — one pass per file:

- **`code-generation-triggers.md`** — item 1 above covers it (G6 table now lives here).
- **`../rewrite-model.md`** — the phrase `"does not navigate to a new table scope"` (in the
  `TableTargetField interface vs. NestingField` subsection) is correct. No other
  lookup-in-scope claims present. No change.
- **`../argument-resolution.md`** — discusses lookup mapping and `@condition` separately.
  Spot-check that wording never implies `@condition` is blocked on lookup fields.
- **`graphitron-codegen-parent/graphitron-java-codegen/README.md`** — primary directive
  reference, ~1500 lines, 32 mentions of `@condition`/`@lookupKey`. Grep for `lookupKey` +
  nearby `condition`/`scope` language; reconcile with the rewritten vocabulary.

For each finding, decide per-case: rewrite in place vs. cross-link to
[code-generation-triggers.md#classification-vocabulary](../code-generation-triggers.md#classification-vocabulary).
Prefer cross-links — one authoritative source is easier to keep correct.

---

## 4. Consider surfacing the "target type" split in `rewrite-model.md`

`rewrite-model.md` colour-codes `TableTargetField` (teal) and renders two Mermaid diagrams of the
sealed hierarchy, but neither diagram exposes the **source context × target type** grid that
`code-generation-triggers.md` now organises classification around. Consider adding a small
table (or third diagram) that lines up:

```
source context  \  target type →   Table       Record      Scalar
Unmapped (root)                    QueryTableField etc.
Table-mapped                       TableField/SplitTableField/LookupTableField/SplitLookupTableField    (column fields)
Result-mapped                      RecordTableField/RecordLookupTableField  RecordField  PropertyField
```

**Low priority.** The variant tables in `code-generation-triggers.md` already cover the same
ground, and a second representation risks drift. Skip this if the main diagrams are working
well enough in practice.

---

## 5. Document the lookup-condition method signature (prerequisite for G5/G6 execution tests)

The rewritten doc says the lookup condition receives the (source × target) pair and must be a
predicate over the pair. That contract is not yet spelled out as a method-signature rule
anywhere — grep confirms zero mentions of `srcAlias`/`tgtAlias`/"source row alias"/"target row
alias" in `graphitron-java-codegen/README.md` or in `graphitron-rewrite` source.

Questions to answer:

- What parameters does the `@condition` method take when the field also has `@lookupKey`?
  (Source row alias + target row alias + user args? Or just the one table context?)
- Which `ParamSource` variants are valid on a lookup condition?
- Is there an execution test that exercises a lookup field with a non-trivial `@condition`
  applied?

**Action.** Before wiring lookup execution tests (see Generator stubs #6–7 in the roadmap), nail down the signature,
document it in
[graphitron-java-codegen README](../../../graphitron-codegen-parent/graphitron-java-codegen/README.md)
alongside `@condition`, and add an execution test in `graphitron-test` that
verifies the N × M contract holds end-to-end.

This item is the real blocker — it gates G5 and G6 execution tests. Items 1–4 are doc
cleanups; item 5 changes what "done" means for those generator stubs.

### Findings from G5 C4 (2026-04-18)

Empirical data from attempting ConditionJoin inline emission during G5 C3/C4:

- **ConditionJoin lacks a resolved target table.** The record is
  `ConditionJoin(MethodRef condition, String alias)` — no `TableRef` for the "table this hop
  navigates to". G5's `JoinPathEmitter` cannot generate a `.as(<alias>)` / `.from(targetAlias)`
  without it. C3's emitter works around this by detecting `JoinPathEmitter.hasConditionJoin(path)`
  and emitting a `throw new UnsupportedOperationException(…)` stub for the arm. Generated code
  compiles; runtime throws only if the field is selected.
- **Schema fixture landed in C4.** `Category.similar` in
  `graphitron-rewrite/graphitron-test/src/main/resources/graphql/schema.graphqls` uses
  `@reference(path: [{condition: {className: "…CategoryConditions", method: "sameNamePrefix"}}])`.
  The classifier builds a `TableField` with `joinPath = [ConditionJoin]` successfully; generated
  code compiles; runtime throw is in place. Item 5 can use this fixture as the ready-made
  target for the "add execution test" work.
- **Proposed ConditionJoin enrichment.** Item 5's design should likely mirror G5's C3a enrichment
  of `FkJoin`: add `TableRef targetTable` to `ConditionJoin` (resolved by the builder via the
  `@reference` directive's enclosing type context, since the target is what the field's return
  type declares). Optionally also resolve `sourceTable` for symmetry with `FkJoin`. Once the
  target is known, the C3 emitter's conditional `if (hasConditionJoin) throw …` branch collapses
  into a uniform `.join(targetTable.as(alias)).on(<ClassName>.<method>(srcAlias, targetAlias))`.
- **Emitter shape for condition-on the JOIN.** For a ConditionJoin as the FIRST step (single-hop
  inline), the emission pattern is:
  `.from(targetAlias).where(<ClassName>.<method>(parentAlias, targetAlias))`. For it as a
  later step, `.join(targetAlias).on(<ClassName>.<method>(prevAlias, targetAlias))`. Two-arg
  call emission already exists as `JoinPathEmitter.emitTwoArgMethodCall`.

Execution-test design: reuse the `Category.similar` fixture. Seed data-driven condition (e.g.
`sameNamePrefix` matches categories whose names share the first letter). Assert runtime shape
for the N × M contract.

---

## 6. `FkJoin.alias` is dead storage

`BuildContext` populates `FkJoin.alias` as `fieldName + "_" + stepIndex` (e.g. `"language_0"`)
while resolving a `@reference` path. No code reads it: the G5 emitter derives its own
aliases via `JoinPathEmitter.generateAliases` from the target table's `javaClassName()` + hop
index, and layers a runtime prefix (`parent.getName() + "_" + suffix`) for self-ref recursion
uniqueness. The stored value is never consulted.

**Change.** Pick one:

- **Drop it.** Remove `alias` from the `FkJoin` record, its `ConditionJoin` sibling, and the
  construction sites in `BuildContext`. Adjust the 9 direct-construction test sites.
- **Use it.** Make `JoinPathEmitter.generateAliases` consume `FkJoin.alias()` when present and
  fall back to the `javaClassName()`-derived form only when empty. This keeps the builder's
  claim on aliasing honest and aligns with the plan's "Alias generation" section.

"Drop it" is the smaller change. "Use it" aligns with the plan's original intent (builder owns
alias identity). Pick based on whether argres Phase 2a or G6 want per-hop aliases threaded
through the model.

**Impact.** Cosmetic. No generated-code difference either way.

---

## 7. `InlineTableFieldEmitter.buildInnerSelect` passes wrong alias to `@condition(filter:)` methods

**Scope of this item.** The `ArgCallEmitter.buildCallArgs` signature change (adding an
explicit source-alias parameter) shipped under the "Generated-fetcher quality pass" entry
in the roadmap (Done) — it fell out of the `table` → `<entity>Table` rename. This item
is the downstream consumer: fix the inline-subquery caller now that the signature is
available.

**Current state.** `buildCallArgs` emits `"table"` as the literal first argument of every
filter-method call. On the fetcher path this is correct — `TypeFetcherGenerator`'s
generated methods receive `table` as a parameter naming the row table. On the G5
inline-subquery path, `"table"` is the **parent** alias (the outer `$fields(sel, table,
env)` parameter), not the terminal/target alias inside the correlated subquery.
`InlineTableFieldEmitter.buildInnerSelect`'s user-filter loop over `tf.filters()` calls
`ArgCallEmitter.buildCallArgs` and so passes the parent alias to each `@condition(filter:)`
method — almost certainly not what users intend, since filters typically operate on the
target table.

No G5 fixture exercises `@condition(filter:)` on an inline `TableField`, so this latent
bug is untested. It will surface the first time an inline reference field carries
`@condition(filter:)`.

**Change.** Once `ArgCallEmitter.buildCallArgs` takes an explicit source-alias parameter,
update the inline-subquery caller to pass the terminal alias of the correlated subquery
instead of the hardcoded `"table"`. Add an `InlineTableFieldEmitter` pipeline test with
a filter method to lock the wiring down.

**Related.** Whether the "source" for an inline-subquery filter is the terminal alias or
the step-0 source alias is a design call — item 5's condition-method signature work
decides this for condition joins; the same answer likely applies here.

---

## Out of scope

- **Renaming `LookupTableField` / `SplitLookupTableField` etc.** No rename is implied by the
  reviewer's comments; the variant names are accurate as long as the *scope* claim they imply
  is not — which the rewritten doc fixes at the vocabulary level.
- **Changes to `@lookupKey` + pagination semantics.** The rewritten doc keeps the existing
  "blocks pagination" rule.
