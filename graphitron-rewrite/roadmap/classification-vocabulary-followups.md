---
id: R3
title: Classification vocabulary follow-ups
status: Spec
priority: 5
theme: docs
depends-on: []
---

# Plan: Classification Vocabulary Follow-ups

> Six independent doc/generator-behaviour cleanups surfaced during the `code-generation-triggers.md` rewrite plus G5's Pending Review sweep. Any of them can land independently; none is a release blocker.

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
- **`../argument-resolution.md`** — discusses lookup mapping and `@condition` separately.
  Spot-check that wording never implies `@condition` is blocked on lookup fields.
- **`graphitron-codegen-parent/graphitron-java-codegen/README.md`** — primary directive
  reference, ~1500 lines, 32 mentions of `@condition`/`@lookupKey`. Grep for `lookupKey` +
  nearby `condition`/`scope` language; reconcile with the rewritten vocabulary.

For each finding, decide per-case: rewrite in place vs. cross-link to
[code-generation-triggers.md#classification-vocabulary](../code-generation-triggers.md#classification-vocabulary).
Prefer cross-links — one authoritative source is easier to keep correct.

---

## 4. Document the lookup-condition method signature (prerequisite for G5/G6 execution tests)

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

This item is the real blocker — it gates G5 and G6 execution tests. Items 1–3 are doc
cleanups; item 4 changes what "done" means for those generator stubs.

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

## 5. `FkJoin.alias` is dead storage

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

## 6. `InlineTableFieldEmitter.buildInnerSelect` passes wrong alias to `@condition(filter:)` methods

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
the step-0 source alias is a design call — item 4's condition-method signature work
decides this for condition joins; the same answer likely applies here.

---

## 7. Same-table `@nodeId` discovery walks the SDL three times per field

After R40 shipped, `FieldBuilder` resolves whether a field's argument set contains a same-table
`@nodeId` leaf in three independent places:

- `findSameTableNodeIdUnderAsConnection` (the `@asConnection` rejection walk in
  `resolveTableFieldComponents`),
- `hasSameTableNodeIdAnywhere` (the lookup-promotion gate that decides between
  `QueryTableField` and `QueryLookupTableField`),
- `classifyArgument` itself (the actual classification that produces the `Resolved.SameTable`
  arm).

All three call `NodeIdLeafResolver.resolve()` per leaf, which in turn does FK lookups and
NodeType-metadata resolution against the catalog. The structural fact "this field has a
same-table @nodeId arg" is now a derived predicate consulted in three places rather than a
property classified once and read three times. The pre-classification walks also force
`NodeIdLeafResolver` to be reentrant on the same leaf within one classification pass.

**Change.** Either:
- Memoise `NodeIdLeafResolver.resolve(leaf, name, table)` on `BuildContext` keyed by the leaf
  identity, so repeated lookups during one schema build cost a single resolver invocation; or
- Invert: classify the field's arguments first, then read the classified `ColumnArg.isLookupKey`
  / `ScalarArg.SameTable`-shaped values for the lookup-promotion gate and the `@asConnection`
  rejection. The `@asConnection` walk into nested input-field leaves still needs SDL traversal
  (input-field classification happens later, on a different code path), but the top-level arg
  side reads off the classified `ArgumentRef` list.

**Impact.** Cleanup. No behaviour change — same shapes accepted/rejected.

---

## 8. Java-17-safe `Record<N>` decode emission duplicated across two emitters

`ArgCallEmitter.buildScalarDecodeBlock` and `LookupValuesJoinEmitter.addRowBuildingCore` both
emit the same Java-17-safe pattern for the `instanceof Record<N> _r` decode check: cast to
`Object` first to make the pattern conditional, match raw `Record1`/`RecordN`, then cast
`_r.value1()` to the column's Java class. Both sites carry a near-identical comment explaining
the Java 17 vs Java 21 distinction.

The duplication is what made the Java-17 bug latent until R40's execution-tier coverage hit it
on both code paths simultaneously; any future tweak (switching to a `Conversions` helper,
adopting a different no-match diagnostic, raising the generated-output baseline to Java 21)
touches both.

**Change.** Lift the scalar-decode `CodeBlock` into a small helper alongside `CallSiteExtraction`
or under `generators/` (`NodeIdDecodeEmit.scalarValueOrThrow(...)` /
`scalarValueOrNull(...)`). Both emitters call the helper. The arity > 1 (`valuesRow`) form is
already shared-spirit; this completes the symmetry.

**Impact.** Cleanup. No emitted-code change; eliminates the drift surface.

---

## Out of scope

- **Renaming `LookupTableField` / `SplitLookupTableField` etc.** No rename is implied by the
  reviewer's comments; the variant names are accurate as long as the *scope* claim they imply
  is not — which the rewritten doc fixes at the vocabulary level.
- **Changes to `@lookupKey` + pagination semantics.** The rewritten doc keeps the existing
  "blocks pagination" rule.
