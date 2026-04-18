# Review Notes — Sealed-Switch & Variant-Coverage Plans (CAUET)

> Branch under review: `claude/review-sealed-switch-plan-CAUET`
> Plans reviewed: `plan-sealed-switch-generator-dispatch.md`, `plan-variant-coverage-meta-test.md`
> Reviewed alongside parallel branches: `review-commits-update-docs-Bin1C`,
> `review-dispatch-plan-1Thn0`, `claude/graphitron-rewrite`, `claude/legacy-platformid-design-66V2h`

---

## Status of each plan

### `plan-sealed-switch-generator-dispatch.md` — fully implemented

All seven implementation steps are in. The commits `e5bc22f1` (initial switch
refactor) and `3357928a` (NOT_IMPLEMENTED_REASONS map + stub helper) cover the
production changes. The D4 validator rule is in `GraphitronSchemaValidator.validateColumnField`
(rejects a `ColumnField` whose parent type is not `TableBackedType`).
`GeneratorCoverageTest.notImplementedReasonsContainsOnlyConcreteSealedLeaves` is live.

**This plan should be marked done** — it currently reads as forward-looking and
will mislead contributors who haven't traced the commits.

### `plan-variant-coverage-meta-test.md` — draft, nothing implemented yet

Status: "Draft. Five design decisions ship with recommended directions." None of
the three phases has any code. The five decisions all recommend option A; they
appear well-reasoned and ready to confirm — but are not confirmed. Phase 1 is a
low-risk ~60-LOC change. Phase 2 is the real lift (~150–180 enum constants across
26 enums).

---

## Gaps and issues in the plans

### 1. Phase 1 has a sequencing dependency not stated anywhere

Phase 1's `NOT_DISPATCHED_LEAVES` includes `InputField.NestingField.class`.
That class does **not** exist on `review-sealed-switch-plan-CAUET` — it is
being added in the parallel branch `review-commits-update-docs-Bin1C`. If Phase 1
is implemented before that branch lands, the `NOT_DISPATCHED_LEAVES` set
references a non-existent class and will not compile.

The plan must state: **Phase 1 depends on `review-commits-update-docs-Bin1C`
(or an equivalent commit adding `InputField.NestingField`) being merged first.**

`InputField.NestingField` in that branch represents an input field whose GraphQL
type is a plain (non-`@table`) grouping type, parallel to `ChildField.NestingField`
on the output side. The fetcher switch handles it correctly without code changes
(caught by the existing `case InputField ignored ->` arm).

### 2. `sealedLeaves` accessibility doesn't work cross-package

`GeneratorCoverageTest` lives in package `no.sikt.graphitron.rewrite.generators`.
`VariantCoverageTest` (Phase 2) is in `no.sikt.graphitron.rewrite`. The Phase 2
plan writes `GeneratorCoverageTest.sealedLeaves(r)` in `VariantCoverageTest`, but
`sealedLeaves` is currently `private static`.

Making it package-private is not enough — the two classes are in different packages.
It must be either:
- **`static Set<Class<?>> sealedLeaves(Class<?> type)`** — public on
  `GeneratorCoverageTest` (simplest), or
- Extracted to a new `SealedLeafUtils` test utility in a shared package.

The plan mentions "factor `sealedLeaves` out to a package-visible utility" without
deciding between these options. Pick one before Phase 1 implementation starts.

### 3. P2 #4 (validator rejects stubbed-variant schemas) has no owning plan

Both plans correctly note P2 #4 as out of scope and deferred. But it is the most
important practical safety property of the `NOT_IMPLEMENTED_REASONS` map: until
the validator consumes `NOT_IMPLEMENTED_REASONS.keySet()`, schemas that use an
unimplemented field type pass validation and produce generated code that crashes
at request time with `UnsupportedOperationException`.

This is worth a standalone plan (analogous to the sealed-switch plan). A single
arm in the existing `GraphitronSchemaValidator` field-dispatch switch, consuming
`NOT_IMPLEMENTED_REASONS.containsKey(field.getClass())`, is all that's needed
production-side. The validator test already has a pattern to follow.

### 4. `GraphitronType` leaf coverage gap in Phase 2 is uncharted

`VariantCoverageTest` (Phase 2) asserts classification coverage for both
`GraphitronField` **and** `GraphitronType` leaves (15 leaves in the type taxonomy).
The plan describes retrofitting the 26 existing enums for `GraphitronField` leaves
but does not enumerate which `GraphitronType` leaves currently have classification
test cases and which do not.

If some `GraphitronType` leaves have no assertion anywhere, the meta-test fails
immediately on first run and requires writing new test cases — not just retrofitting
existing ones. Auditing this before Phase 2 starts would prevent surprises.

### 5. `InputField.NestingField` is recursive — Phase 2 needs a note

`InputField.NestingField` carries `List<InputField> fields`. A classification case
that produces a `NestingField` legitimately covers multiple `InputField` leaf
variants in a single SDL snippet (the nested fields are resolved to their own
leaf types at classify time). Phase 2's plan for `variants() = Set.of(...)` should
note this: `NestingField` cases should declare all `InputField` leaf types present
in their nested `fields`, not just `NestingField.class` itself.

### 6. NOT_IMPLEMENTED_REASONS per-family count in plan text is stale

The variant-coverage plan states "33 entries across QueryField (6) / MutationField (6)
/ ChildField (21)." The actual map has QueryField (8) / MutationField (6) / ChildField (19)
= 33. Total is right; the per-family breakdown is wrong. Minor, but worth correcting
before Phase 2 where contributors cross-reference these numbers.

---

## Parallel branch findings

### `review-commits-update-docs-Bin1C`

Adds `InputField.NestingField` (4 commits: model, classifier, validator, tests,
and a Javadoc-only `ParticipantRef` clarification). After merge:

- `TypeFetcherGenerator` switch remains exhaustive with no changes — `case InputField ignored ->`
  covers all InputField subtypes.
- `GeneratorCoverageTest` existing test continues to pass — it only checks that
  `NOT_IMPLEMENTED_REASONS` keys are valid sealed leaves, not the reverse.
- Phase 1 `NOT_DISPATCHED_LEAVES` becomes compilable (as written, it anticipates
  this addition).

### `review-dispatch-plan-1Thn0`

The **pre-review draft** of the sealed-switch plan — uses per-variant stub methods
(D2 option a) rather than the `NOT_IMPLEMENTED_REASONS` map (D2 option b, adopted
in CAUET). This branch is superseded and should be archived.

### `claude/graphitron-rewrite`

Argument-resolution foundation has landed: `classifyArguments`, projection helpers,
`LookupField` capability interface, `LookupMapping` narrow component. Impact on
the variant-coverage plan:

- Phase 3 (narrow component coverage) scope grows — `LookupField`, `LookupMapping`
  are new narrow component sealed types that would need coverage.
- `LookupField` is shared across four lookup variants (`QueryLookupTableField`,
  `LookupTableField`, `SplitLookupTableField`, `RecordLookupTableField`). When
  those stubs are implemented and removed from `NOT_IMPLEMENTED_REASONS`, their
  `IMPLEMENTED_LEAVES` entries will carry `LookupField` semantics — worth noting
  in the comment blocks.

### `claude/legacy-platformid-design-66V2h`

Deletes `docs/plan-drop-common-dependency.md` and folds a summary into the roadmap
backlog. This conflicts with `claude/review-66v2h-plan-8hrlc` which still has the
full plan file. Resolve on merge: keep the roadmap summary; the standalone plan file
can be removed or kept as detailed reference per team preference.

---

## Summary checklist before implementing Phase 1

- [ ] Merge `review-commits-update-docs-Bin1C` (or cherry-pick `InputField.NestingField`
      addition) so the `NOT_DISPATCHED_LEAVES` set compiles.
- [ ] Decide `sealedLeaves` visibility: `public static` on `GeneratorCoverageTest`
      or extract to `SealedLeafUtils`.
- [ ] Mark `plan-sealed-switch-generator-dispatch.md` as done (add status line at top).
- [ ] Archive `review-dispatch-plan-1Thn0` (stale pre-review draft).

## Summary checklist before implementing Phase 2

- [ ] Audit `GraphitronType` leaves: list which have classification test cases and
      which would need new cases written.
- [ ] Note in the plan that `InputField.NestingField` cases should declare all
      transitively covered `InputField` leaf types in their `variants()` set.
- [ ] Correct the per-family count in the plan text (QueryField 8, ChildField 19).
- [ ] Confirm all five decisions in the plan (currently "recommended directions"
      without explicit sign-off).

## Suggested next step

Write a standalone plan for P2 #4 (validator consuming
`NOT_IMPLEMENTED_REASONS.keySet()`). It is a small, self-contained change with
high practical value and a clear implementation shape (one arm in the
`GraphitronSchemaValidator` field switch). It should land before Phase 1 of the
variant-coverage plan so the validator test can use `NOT_DISPATCHED_LEAVES` as
its allowlist.
