---
id: R8
title: Docs as an index into classification tests
status: Ready
deferred: true
priority: 10
theme: docs
depends-on: []
---

# Plan: Docs as an Index into the Classification Tests

> Steps 1-2 shipped on `claude/review-docs-plan-adYJW`. Step 5 is superseded by the variant-coverage meta-test — `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` + `VariantCoverageTest.everySealedLeafHasAClassificationCase`. Steps 3-4 remain, deferred until the sealed hierarchy stabilises (Active work and Stubs still in motion).

Goal: position `code-generation-triggers.md` as a **map** into the existing classification tests, so that the detailed truth table (every schema pattern → every variant) lives as executable spec, and the doc engages the reader by pointing into it. No deletion — the doc keeps its tables as a one-glance overview; each table row ends with a pointer to the test that asserts it.

Scope: `GraphitronSchemaBuilderTest` (≈ 2 250 lines, ≈ 25 enums, ≈ 150 enum cases) and the Classification tables in `docs/code-generation-triggers.md`. Out of scope: the per-variant `*ValidationTest` files — those test validation rules on already-classified fields, not schema → variant mapping.

---

## What we have today

**Doc side.** The Classification Vocabulary (source context, target type, scope, derived
tables, conditions, structural properties) is conceptual and not mirrored in tests — it stays
as doc-only prose. The four classification tables (Type Classification, Query Fields, Mutation
Fields, Child Fields on `@table` / on `@record`, Input Fields on `@table` input parent) are
1-row-per-variant truth tables — these are the duplication with the tests.

**Test side.** `GraphitronSchemaBuilderTest` already organises itself around the same variants:
`// ===== VariantName =====` section headers, one enum per family, one enum constant per case,
each constant a `(description, SDL, assertion)` triple. Descriptions are mostly of the form
`"<schema trigger> → VariantName"` which reads like a doc table row.

---

## Step 3 — Re-section for stable anchors (deferred)

Goal: pointer stability for Step 4 links. Two sub-options:

**Option A (light touch).** Keep `GraphitronSchemaBuilderTest.java` monolithic but:
- Rename `TypeClassificationCase` → `TableTypeCase`.
- Rename `NonTableParentCase` → `ChildFieldOnRecordParentCase`.
- Split `InterfaceUnionFieldCase` into three, or rename its cases to `<VARIANT>_…`.
- Replace `// ===== VariantName =====` headers with a uniform `// ===== VariantName (doc: §…) =====`.

**Option B (split file).** Move each enum + its parameterised method to its own file under a
new `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/classification/` package.
Each file ~100–200 lines, one-to-one with a doc section.

Trade-off: A is faster and less risky; B gives IDE-level "file name = doc section" anchors
and makes file-URL links in the doc line-stable across unrelated edits. Favour B if this
pattern is permanent; A if still experimental.

Estimate: A ~½ day. B ~1–2 days.

---

## Step 4 — Rewire the doc (deferred)

Waits on Step 3 so the pointers are line-stable.

In `code-generation-triggers.md`:

- **Keep** the Classification Vocabulary section (conceptual, no test equivalent).
- **Keep** the four classification tables, but shrink the *Generator Output* column to 3–5
  words.
- **After each table**, add a pointer to the corresponding enum with a file:line link.
- **Drop in-doc SDL snippets** that duplicate a test case's SDL; link to the case instead.
- **Add a short reading guide** at the top.

Estimate: ~½ day.

---

## Recommended order (when resuming)

1. **Step 3** (re-section / rename).
2. **Step 2 remainder** (normalise descriptions in remaining enums, piggybacks on Step 3's moves).
3. **Step 4** (rewire doc, waits on stable anchors from Step 3).

---

## Out of scope

- **Auto-generating the doc from the tests.** Revisit after Step 4 if the tables still feel
  redundant next to the pointers.
- **Renaming variants.** The sealed-type names are accurate; the doc's scope/orthogonality
  claim was the problem, not the names.
- **Per-variant `*ValidationTest` files.** These exercise validation rules on constructed
  fields, not schema → variant classification.
