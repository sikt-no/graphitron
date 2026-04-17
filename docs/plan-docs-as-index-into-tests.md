# Plan — Docs as an Index into the Classification Tests

Goal: position `code-generation-triggers.md` as a **map** into the existing classification tests,
so that the detailed truth table (every schema pattern → every variant) lives as executable
spec, and the doc engages the reader by pointing into it. No deletion — the doc keeps its
tables as a one-glance overview; each table row ends with a pointer to the test that asserts it.

Scope: `GraphitronSchemaBuilderTest` (≈ 2 250 lines, ≈ 20 enums, ≈ 150 enum cases) and the
Classification tables in `docs/code-generation-triggers.md`. Out of scope: the per-variant
`*ValidationTest` files — those test validation rules on already-classified fields, not
schema → variant mapping.

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

This is the right shape. What's missing is (a) coverage of a handful of doc rows, (b) uniform
style across cases, (c) stable anchors the doc can link to.

---

## Review findings

### Coverage gaps — doc rows with no matching test case

| Doc section | Missing variant | Current test enum |
|---|---|---|
| Query Fields | `QueryServiceRecordField` | `RootFieldCase` |
| Mutation Fields | `MutationServiceRecordField` | `RootFieldCase` |
| Child on `@table` | `SplitLookupTableField` (`@splitQuery + @lookupKey`) | no case in `TableFieldCase` |
| Child on `@record` | `RecordTableField` (target `@table`, no `@lookupKey`) | `NonTableParentCase` |
| Child on `@record` | `RecordLookupTableField` (target `@table` + `@lookupKey`) | `NonTableParentCase` |
| Child on `@record` | `RecordField` (target non-table) | `NonTableParentCase` |
| Child on `@record` | `ServiceTableField` on `@record` parent | `NonTableParentCase` |

We cannot invite readers into the tests while rows silently lack a case.

### Organisation and naming issues

- **`TypeClassificationCase`** (`GraphitronSchemaBuilderTest.java:1423`) is misnamed — it only
  covers `TableType`. `NodeType`, `ResultType`, `TableInterfaceType`, `InterfaceType`,
  `UnionType`, `ErrorType`, `InputType`, `TableInputType`, `UnclassifiedType` each have their
  own enums. Rename to `TableTypeCase`.
- **`NonTableParentCase`** (`:975`) is vague — it's "child field on `@record` parent". Rename
  to `ChildFieldOnRecordParentCase` so its scope matches the doc's *Child Fields (on `@record`
  parent)* table.
- **`InterfaceUnionFieldCase`** (`:926`) groups three variants in one enum. Fine for shared
  SDL boilerplate, but the 1:1 "doc row ↔ test case" mapping a reader tries to follow
  breaks. Either split into `TableInterfaceFieldCase` / `InterfaceFieldCase` /
  `UnionFieldCase`, or keep the single enum with case names that begin with the variant
  produced (`TABLE_INTERFACE_FIELD_…`, `INTERFACE_FIELD_…`, `UNION_FIELD_…`).
- **Section headers** (`// ===== VariantName =====`) are the implicit index, but they aren't
  line-stable and IDE-navigable from a Markdown doc. Pointer stability improves once we split
  or anchor (see Step 4).

### Case-description style — varies

Two patterns co-exist:

- **Table-row style** (preferred): `"@externalField on a @table parent → ComputedField"` —
  `ComputedFieldCase.EXTERNAL_FIELD_DIRECTIVE`.
- **Behaviour prose** (works, less scannable): `"object return type → Single cardinality,
  null condition, empty joinPath"` — `TableFieldCase.SINGLE_RETURN_TYPE`.

Every case already has the ingredients for the table-row style (trigger + variant). Unifying
the wording is cheap and buys a lot when the doc links say "one row per case here."

### Conceptual content — stays in doc

The following is doc-only and not reflected in any test:
- Source context vs. target type (the two-axis vocabulary)
- Scope boundaries (Enter / Split / Record handoff / Exit)
- Record handoff mechanics
- Derived source/target tables
- N × M positional contract for lookup fields
- Condition kinds (Reference / Filter / Lookup)
- `@splitQuery` warning-not-error on `@record` parents
- `@lookupKey` orthogonality to scope

---

## Plan

Five small independent steps. Each can land as its own commit/PR.

### Step 1 — Close the coverage gaps (test-only)

Add the enum cases in the table above so every doc row has a landing spot. Keep them in the
enums already covering the relevant parent context; new enum constants only, no refactor:

- `NonTableParentCase`: add `RECORD_TABLE_FIELD`, `RECORD_LOOKUP_TABLE_FIELD`, `RECORD_FIELD`,
  `SERVICE_TABLE_FIELD_ON_RECORD_PARENT`.
- `TableFieldCase` (or dedicated `SplitLookupTableFieldCase` nearby): add `SPLIT_LOOKUP_TABLE_FIELD`.
- `RootFieldCase`: add `QUERY_SERVICE_RECORD_FIELD`, `MUTATION_SERVICE_RECORD_FIELD`.

Estimate: ~1 day. No production code change.

### Step 2 — Normalise case-description style (test-only)

One rule: **every enum-case description starts with the schema trigger, ends with
`→ VariantName`, and names at most one additional invariant the case exists to exercise.**

Before:
```
"object return type → Single cardinality, null condition, empty joinPath"
```
After:
```
"@table return type (default) → TableField (Single, empty joinPath)"
```

Additionally, add a one-paragraph Javadoc on each enum describing the axis it exercises —
"Child field on `@table` parent, object return type: one case per variant the builder can
produce from that shape. Covers the *Child Fields (on `@table` parent) — Object return type*
table in `code-generation-triggers.md`."

Estimate: ~1 day. Mechanical rewrite.

### Step 3 — Re-section for stable anchors (test-only)

Goal: pointer stability. Two sub-options, to be decided before starting:

**Option A (light touch).** Keep `GraphitronSchemaBuilderTest.java` monolithic but:
- Rename `TypeClassificationCase` → `TableTypeCase`.
- Rename `NonTableParentCase` → `ChildFieldOnRecordParentCase`.
- Split `InterfaceUnionFieldCase` into three, or rename its cases to `<VARIANT>_…`.
- Replace `// ===== VariantName =====` headers with a uniform `// ===== VariantName (doc: §Child Fields on @table — object return) =====` to make doc ↔ test linkage explicit in the source.

**Option B (split file).** Move each enum + its parameterised method to its own file under a
new `graphitron-rewrite/src/test/java/no/sikt/graphitron/rewrite/classification/` package
(`TableTypeClassificationTests.java`, `ChildFieldOnTableParentObjectReturnTests.java`, …).
Each file ~100–200 lines, one-to-one with a doc section. Shared helpers stay in a small
`ClassificationTestSupport` class.

Trade-off: A is faster and less risky; B gives IDE-level "file name = doc section" anchors
and makes file-URL links in the doc line-stable across unrelated edits. I'd favour B if we
commit to this doc-as-index pattern for the long haul, A if this is an experiment.

Estimate: A ~½ day. B ~1–2 days including test support extraction.

### Step 4 — Rewire the doc (doc-only)

In `code-generation-triggers.md`:

- **Keep** the Classification Vocabulary section (conceptual, no test equivalent).
- **Keep** the four classification tables, but shrink the *Generator Output* column to 3–5
  words — the prose belongs next to generator source, not here.
- **After each table**, add a pointer:
  > **Spec:** `GraphitronSchemaBuilderTest.TableTypeCase` — one parameterised case per row in
  > this table.
  With a concrete file:line link.
- **Drop in-doc SDL snippets** that duplicate a test case's SDL; link to the case instead.
- **Add a short reading guide** at the top:
  > The vocabulary below is the map; the classification tests under
  > `graphitron-rewrite/src/test/.../GraphitronSchemaBuilderTest.java` are the specification.
  > Every row in every table in this doc is backed by at least one runnable case there.

Estimate: ~½ day.

### Step 5 — Keep drift out with a meta-test (test-only)

Add one structural test that prevents the doc-as-index promise from silently rotting:

```java
@Test
void everyClassificationVariantHasAtLeastOneCase() {
    var variants = reflectPermits(GraphitronField.class) + reflectPermits(GraphitronType.class);
    var covered = Arrays.stream(allEnumsInTestClass())
        .flatMap(Enum::values)
        .map(Object::toString)   // the description
        .collect(toSet());
    for (var variant : variants) {
        assertThat(covered).anyMatch(d -> d.contains(variant.simpleName()))
            .withFailMessage("No classification case mentions %s — add one, or add to an allowlist if it is deliberately unclassifiable", variant.simpleName());
    }
}
```

Catches "new sealed-hierarchy permit, no test case, silent doc gap" automatically. An
allowlist handles intentional exclusions (`UnclassifiedField`, intermediate sealed
interfaces).

Estimate: ~1–2 hours.

---

## Order of work

Recommended order, assuming Step 3 Option B:

1. **Step 1** (close gaps) — independent; lands alone and makes Step 4 possible.
2. **Step 3** (re-section) — independent once Step 1 is in. Lands as pure refactor/rename.
3. **Step 2** (normalise descriptions) — piggybacks on Step 3's file moves naturally.
4. **Step 4** (rewire the doc) — waits on Step 3 so the pointers are line-stable.
5. **Step 5** (meta-test) — lands last to lock the invariant.

If Step 3 Option A instead, Step 2 runs before Step 3 (less churn) and the order becomes
1 → 2 → 3 → 4 → 5.

---

## Out of scope

- **Auto-generating the doc from the tests.** Tempting — the enum descriptions could become
  the table rows directly. Not worth it yet: the doc is conceptual prose glued *around* the
  tables; generation would either drop the prose or produce awkward interleaving. Revisit
  after Step 4 if the tables still feel redundant next to the pointers.
- **Renaming variants.** The sealed-type names (`LookupTableField`, `RecordTableField`, …)
  are accurate; the doc's scope/orthogonality claim was the problem, not the names. Already
  fixed in the Classification Vocabulary rewrite.
- **Per-variant `*ValidationTest` files.** These exercise validation rules on constructed
  fields, not schema → variant classification. Their readability is a separate concern; they
  already have an obvious 1:1 file-per-variant mapping.

---

## Open questions for review

1. **Step 3 — Option A (rename only) or Option B (split file).** Which are we committing to?
2. **Doc table *Generator Output* column** — shrink to 3–5 words (as proposed), or keep the
   current detail and lean on the pointer for the "what triggers what" side only?
3. **Meta-test detection strategy** — matching `description.contains(variantName)` is
   fragile against description rewording. Alternative: tag each case with the variant class
   explicitly (extra enum field `Class<?> variant`) and assert on that instead. Probably
   worth doing if we take Step 3 Option B.
