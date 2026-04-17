# Plan — Classification Vocabulary Follow-ups

Follow-up items surfaced while addressing review comments on
[code-generation-triggers.md](code-generation-triggers.md). Each item covers one place where the
doc or the code still treats `@lookupKey` as scope-defining, mis-states the `@condition` rule,
or is missing the new **source context vs. target type** split.

Prioritised rough-to-low effort. No code change is tied to a release — these are doc and
generator-behaviour cleanups that can land independently.

---

## 1. `rewrite-roadmap.md` G6 table — `@condition` is not blocked on lookup fields

The G6 "Split/Lookup field categories" table in
[rewrite-roadmap.md](rewrite-roadmap.md) still says `@condition` is *blocked* on every lookup
variant (`LookupQueryField`, table-mapped `LookupTableField`, result-mapped
`LookupTableField`). This is the same framing the reviewer rejected in
`code-generation-triggers.md`:

> `@condition` is not blocked on lookup fields. They must adhere to the contract however.

**Change.** Replace "Blocked (lookup invariant)" in the `@condition` column with "Allowed — must
preserve N × M contract" (or equivalent) across all four rows. Add a short note under the table
citing the rewritten [Derived tables](code-generation-triggers.md#derived-tables) section as the
authoritative statement of the contract.

Verify `FieldBuilder` never rejects `@condition` + `@lookupKey` at classification time (it does
not today — see `FieldBuilder.java:559–627`). No code change expected.

---

## 2. Emit a build warning for `@splitQuery` on a result-mapped parent

`FieldBuilder.classifyChildFieldOnResultType` (`FieldBuilder.java:1078–1138`) does not read
`@splitQuery` at all. On a `@record` parent the directive is silently ignored because the record
handoff already opens a new DataLoader-backed scope — the split is redundant.

Silent acceptance is a trap: a developer adding `@splitQuery` to "make batching kick in" has
no way to discover it was a no-op. The reviewer asked for a **warning** (not an error).

**Change.**
- In `classifyChildFieldOnResultType`, check for `@splitQuery`. If present, attach a warning
  (via whatever warning channel the builder exposes — extend it if there isn't one yet; look at
  how `F1`-style messages are reported today).
- Keep producing `RecordTableField` / `RecordLookupTableField` as today; the directive is purely
  informational on this source context.
- Test: add a pipeline test with a `@record` parent and a `@table`-returning child carrying
  `@splitQuery`, asserting (a) classification still succeeds, (b) a warning is reported, and
  (c) the field variant is still `RecordTableField` / `RecordLookupTableField`.

---

## 3. Audit other docs for lookup-in-scope and condition-blocked wording

The reviewer flagged "Please check if we make the same error elsewhere." The systematic audit:

- `docs/rewrite-roadmap.md` — item 1 above covers it.
- `docs/rewrite-model.md` — scope is mentioned only in passing
  (`"does not navigate to a new table scope"`, line 251). No incorrect lookup-in-scope claim.
- `docs/argument-resolution.md` — discusses lookup mapping and `@condition` separately. Spot-
  check that the wording never implies `@condition` is blocked.
- `graphitron-codegen-parent/graphitron-java-codegen/README.md` — primary directive reference,
  1500+ lines. Grep for `lookupKey` + nearby `condition`/`scope` language; reconcile with the
  rewritten vocabulary.

For each doc, decide per-finding: rewrite vs. cross-link to
`code-generation-triggers.md#classification-vocabulary`. Prefer cross-links — one authoritative
source is easier to keep correct.

---

## 4. Consider surfacing the "target type" split in `rewrite-model.md`

`rewrite-model.md` already colour-codes `TableTargetField` (teal). The new "Target type"
vocabulary makes a distinction the diagram doesn't — field variants split by both source *and*
target. Consider adding a small diagram or table that lines up:

```
source context  \  target type →   Table       Record      Scalar
Unmapped (root)                    QueryTableField etc.
Table-mapped                       TableField/SplitTableField/LookupTableField/SplitLookupTableField    (column fields)
Result-mapped                      RecordTableField/RecordLookupTableField  RecordField  PropertyField
```

Low priority — the variant tables in `code-generation-triggers.md` already cover the same
ground, and a second representation risks drifting.

---

## 5. Lookup condition method contract — document & test the signature

The rewritten doc says the lookup condition receives the (source × target) pair and must be a
predicate over the pair. That contract is not yet spelt out as a method-signature rule:

- What parameters does the `@condition` method take when the field also has `@lookupKey`?
  (Source row alias + target row alias + user args? Or just the one table context?)
- Which `ParamSource` variants are valid on a lookup condition?
- Is there an execution test that exercises a lookup field with a non-trivial `@condition`
  applied?

Action: before wiring lookup execution tests (see
[rewrite-roadmap.md](rewrite-roadmap.md) G5/G6), nail down the signature, document it in the
[graphitron-java-codegen README](../graphitron-codegen-parent/graphitron-java-codegen/README.md)
alongside `@condition`, and add an execution test in `graphitron-rewrite-test-spec` that
verifies the N × M contract holds end-to-end.

---

## Out of scope for this pass

- Renaming `LookupTableField` / `SplitLookupTableField` etc. No rename is implied by the
  reviewer's comments; the variant names are accurate as long as the *scope* claim they imply
  is not — which the rewrite now fixes at the vocabulary level.
- Changes to `@lookupKey` + pagination semantics. The rewritten doc keeps the existing "blocks
  pagination" rule.
