# Plan — Docs as an Index into the Classification Tests

> **Status:** Approved
>
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

## Step 1 — Close the coverage gaps ✅

Every doc table row now has a matching test case. Added:

- `TableFieldCase.SPLIT_LOOKUP_TABLE_FIELD` — `@splitQuery + @lookupKey` on `@table` parent → `SplitLookupTableField`
- `NonTableParentCase.RECORD_TABLE_FIELD` — `@record` parent, `@table` return type (no `@lookupKey`) → `RecordTableField`
- `NonTableParentCase.RECORD_LOOKUP_TABLE_FIELD` — `@record` parent, `@table` return type + `@lookupKey` → `RecordLookupTableField`
- `NonTableParentCase.RECORD_FIELD` — `@record` parent, non-table object return type → `RecordField`
- `NonTableParentCase.SERVICE_TABLE_FIELD_ON_RECORD_PARENT` — `@record` parent + `@service`, `@table` return → `ServiceTableField`
- `RootFieldCase.QUERY_SERVICE_RECORD_FIELD` — root query, `@service`, non-table return → `QueryServiceRecordField`
- `RootFieldCase.MUTATION_SERVICE_RECORD_FIELD` — mutation, `@service`, non-table return → `MutationServiceRecordField`

---

## Step 2 — Normalise case-description style ✅

One rule applied: every enum-case description starts with the schema trigger, ends with
`→ VariantName`, and names at most one additional invariant. Applied to the worst offenders in
`TableFieldCase` and the three modified enums; other enums left for Step 3 when files are
split/renamed.

Added per-enum Javadoc to `TableFieldCase`, `NonTableParentCase`, and `RootFieldCase` — one
paragraph each describing the axis exercised and linking to the relevant table in
`code-generation-triggers.md`.

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

## Step 5 — Keep drift out with a meta-test (deferred)

Define a `ClassificationCase` interface with an explicit `Class<?> variant` field on each enum
case. The meta-test collects all `variant` fields and asserts that every non-allowlisted
sealed-type permit in `GraphitronField` and `GraphitronType` is represented.

This is strictly more reliable than matching on description strings — description rewording can
never cause a false negative, and a new sealed permit with no test case is caught at compile
or test time immediately.

Do the interface + field shape before writing new cases; retrofit existing cases during Step 3.

```java
// On each test-case enum:
enum TableFieldCase implements ClassificationCase {
    SINGLE_RETURN_TYPE("@table return type (default) → TableField (Single, empty joinPath)",
                       TableField.class, "<sdl>"),
    // …
    ;
    final String description;
    final Class<?> variant;
    final String sdl;
}

// In the meta-test:
@Test
void everyClassificationVariantHasAtLeastOneCase() {
    var covered = allCases().stream()
        .map(ClassificationCase::variant)
        .collect(toSet());
    var allowlist = Set.of(UnclassifiedField.class, /* deliberate exclusions */);
    var required = permitsOf(GraphitronField.class, GraphitronType.class).stream()
        .filter(v -> !allowlist.contains(v))
        .collect(toList());
    for (var variant : required) {
        assertThat(covered)
            .as("No classification case covers %s — add one or add to the allowlist",
                variant.getSimpleName())
            .contains(variant);
    }
}
```

Estimate: interface + meta-test ~1–2 hours; retrofitting existing cases ~½ day.

---

## Recommended order (when resuming)

1. **Step 5 (interface + field shape only)** — before writing any new cases in future, so they
   use the final shape from the start.
2. **Step 3** (re-section / rename).
3. **Step 2 remainder** (normalise descriptions in other enums, piggybacks on Step 3's moves).
4. **Step 4** (rewire doc, waits on stable anchors from Step 3).
5. **Step 5 (meta-test body)** — lock the invariant once all cases are in final locations.

---

## Out of scope

- **Auto-generating the doc from the tests.** Revisit after Step 4 if the tables still feel
  redundant next to the pointers.
- **Renaming variants.** The sealed-type names are accurate; the doc's scope/orthogonality
  claim was the problem, not the names.
- **Per-variant `*ValidationTest` files.** These exercise validation rules on constructed
  fields, not schema → variant classification.
