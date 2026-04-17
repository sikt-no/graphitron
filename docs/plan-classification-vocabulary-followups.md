# Plan — Classification Vocabulary Follow-ups

Doc and generator-behaviour cleanups surfaced while rewriting
[code-generation-triggers.md](code-generation-triggers.md). Each item corrects a place where the
doc or the code still treats `@lookupKey` as scope-defining, mis-states the `@condition` rule,
or is missing the new **source context vs. target type** split.

None of these is a release blocker; they can land independently and in any order. Items
are prioritised rough-to-low effort.

**Claim verification (2026-04-17)**: all item claims below were re-verified against the branch
after the sealed-switch work landed. Source line numbers in the original draft had drifted ~20–30
lines; this rewrite uses identifier-level references instead so the items don't go stale with
every refactor.

---

## 1. Roadmap G6 table — `@condition` is not blocked on lookup fields

The G6 **Split/Lookup field categories** table in
[rewrite-roadmap.md](rewrite-roadmap.md#g6--splitlookup-field-categories) still lists
`@condition` as *Blocked* on three of the four lookup variants (`LookupQueryField`,
table-mapped `LookupTableField`, result-mapped `LookupTableField`). The rewritten
[code-generation-triggers.md](code-generation-triggers.md) now states the opposite:

> `@condition` on lookup fields is allowed. The condition method, however, must preserve the
> N × M positional contract…

**Change.** Replace "Blocked (lookup invariant)" / "Blocked" in the `@condition` column with
"Allowed — must preserve N × M contract" (or equivalent) on all three lookup rows. Add a short
pointer under the table to the authoritative statement in
[Derived tables](code-generation-triggers.md#derived-tables).

**Code check (no change expected).** `FieldBuilder.buildFilters` rejects `@condition` only on
*arguments*, never on field definitions, and no classifier pairs `@lookupKey` + `@condition` as
mutually exclusive. The doc is the only place that's wrong.

---

## 2. Emit a build warning for `@splitQuery` on a result-mapped parent

`FieldBuilder.classifyChildFieldOnResultType` does not read `@splitQuery` at all. On a `@record`
parent the directive is silently ignored because the record handoff already opens a new
DataLoader-backed scope — the split is redundant.

[code-generation-triggers.md](code-generation-triggers.md) already states this should be a
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

**Reusability.** The warnings channel will also serve P2 #3 ("validator asks can-this-generate")
if we want `NOT_IMPLEMENTED_REASONS` hits to be warnings rather than errors in some
configurations. Worth keeping that in mind while designing the channel.

---

## 3. Audit other docs for lookup-in-scope and condition-blocked wording

Systematic audit — one pass per file:

- **`docs/rewrite-roadmap.md`** — item 1 above covers it.
- **`docs/rewrite-model.md`** — the phrase `"does not navigate to a new table scope"` (in the
  `TableTargetField interface vs. NestingField` subsection) is correct. No other
  lookup-in-scope claims present. No change.
- **`docs/argument-resolution.md`** — discusses lookup mapping and `@condition` separately.
  Spot-check that wording never implies `@condition` is blocked on lookup fields.
- **`graphitron-codegen-parent/graphitron-java-codegen/README.md`** — primary directive
  reference, ~1500 lines, 32 mentions of `@condition`/`@lookupKey`. Grep for `lookupKey` +
  nearby `condition`/`scope` language; reconcile with the rewritten vocabulary.

For each finding, decide per-case: rewrite in place vs. cross-link to
[code-generation-triggers.md#classification-vocabulary](code-generation-triggers.md#classification-vocabulary).
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

**Action.** Before wiring lookup execution tests (see
[rewrite-roadmap.md G5/G6](rewrite-roadmap.md#g5--inline-tablefield)), nail down the signature,
document it in
[graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md)
alongside `@condition`, and add an execution test in `graphitron-rewrite-test-spec` that
verifies the N × M contract holds end-to-end.

This item is the real blocker — it gates G5 and G6 execution tests. Items 1–4 are doc
cleanups; item 5 changes what "done" means for those generator stubs.

---

## Out of scope

- **Renaming `LookupTableField` / `SplitLookupTableField` etc.** No rename is implied by the
  reviewer's comments; the variant names are accurate as long as the *scope* claim they imply
  is not — which the rewritten doc fixes at the vocabulary level.
- **Changes to `@lookupKey` + pagination semantics.** The rewritten doc keeps the existing
  "blocks pagination" rule.
