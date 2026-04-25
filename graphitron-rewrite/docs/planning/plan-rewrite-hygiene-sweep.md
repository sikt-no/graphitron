# Plan: Rewrite emitter + classifier hygiene sweep

> **Status:** Ready
>
> Three cleanups surfaced during the split-query-connection work (shipped at
> `3821842` §1 + `62b51c3` §2). Bundled into one plan because each gates a
> distinct piece of upcoming feature work cleanly:
>
> - **Phase 1** (`SplitRowsMethodEmitter` dedup) gates *Faceted search on
>   `@asConnection`*, which is likely to add a fourth Split-rows variant.
>   Doing it first means faceted-search authors a body, not a fourth copy.
> - **Phase 2** (Table-parameter convention audit) is a low-cost rule-write;
>   per the audit below every existing helper already complies, so this
>   reduces to a one-liner on each emitter plus a paragraph in design
>   principles.
> - **Phase 3** (rejection-message taxonomy) gates *Mutation bodies*, which
>   will add the largest single batch of new rejection sites in the rewrite's
>   history. Categorising up-front is cheaper than retrofitting later.
>
> All three ship under one plan / one InReview cycle to keep workflow
> overhead low. The phases are still landed in distinct commits.

## Problem

Implementing `@asConnection` on `@splitQuery` produced three code-shape
observations that are hygiene, not blockers:

1. `SplitRowsMethodEmitter` now has three 150-line siblings (`buildListMethod`,
   `buildSingleMethod`, `buildConnectionMethod`) that share a VALUES + FK-chain
   prelude and diverge at projection + outer wrap. `buildConnectionMethod`
   was written by copying `buildListMethod` and mutating, which will rot the
   next time a Split variant needs a fourth copy.
2. Emitted helpers that reference a specific aliased jOOQ Table disagree on
   whether the Table is a parameter or a local declaration. `<fieldName>Condition`
   takes it as a parameter (post fetcher-quality §2); `<fieldName>OrderBy` takes
   it as a parameter now (post split-query-connection §2); `*InputRows`
   (`LookupValuesJoinEmitter`) has the question unaudited. A Split-shaped
   caller that wants to reuse `*InputRows` tomorrow will hit the same alias
   mismatch that blocked dynamic `@orderBy` on Split+Connection for a release.
3. Classifier/validator rejection messages collapse four distinct categories
   into free-form strings. "`@asConnection` on `@splitQuery`" was labeled
   "not yet supported; plan §2" until a reviewer flagged that it is actually
   permanently invalid. Downstream consumers (the Maven plugin, error-aggregation
   tooling, schema-author-facing docs) have no machine-readable signal to
   distinguish "generator deferred" from "schema modeling error" from
   "internal invariant".

## Phase 1: deduplicate `SplitRowsMethodEmitter` siblings

`SplitRowsMethodEmitter` (file:
`graphitron-rewrite/graphitron-rewrite/src/main/java/no/sikt/graphitron/rewrite/generators/SplitRowsMethodEmitter.java`)
holds three siblings that are byte-identical for ~50 lines of prelude:

- `buildListMethod` (lines 276–502, prelude at 326–375)
- `buildSingleMethod` (lines 531–663, prelude at 571–607)
- `buildConnectionMethod` (lines 692–909, prelude at 747–787)

The prelude in each sibling is the same five-act block: `keys.isEmpty()`
short-circuit; `dsl` resolution; typed `Row<N+1>` VALUES `parentRows[]` with
its `@SuppressWarnings({"unchecked","rawtypes"})` cast; `parentInput`
derived-table aliasing; FK-chain `alias_i = Tables.X.as(fieldName_alias_i)`
declarations. Single-cardinality writes the FK-chain step inline (lines
602–607) instead of looping; that is the same emission with `path.size() == 1`
unrolled.

### Concrete shape

New private static helper on `SplitRowsMethodEmitter`:

```java
private record PreludeBindings(
    List<String> aliases,
    String terminalAlias,
    String firstAlias,
    JoinStep.FkJoin firstHop,
    TypeName parentRowType,
    TypeName parentRecordType,
    TypeName parentInputTableType,
    TypeName keyElement,
    List<ColumnRef> pkCols
) {}

private static PreludeBindings emitParentInputAndFkChain(
    CodeBlock.Builder body,
    String fieldName,
    BatchKey.RowKeyed rowKeyed,
    ReturnTypeRef.TableBoundReturnType returnType,
    List<JoinStep> joinPath,
    String jooqPackage)
```

The helper writes the five-act prelude into `body` and returns the bindings
each sibling needs at its divergence point: the alias list, the typed Row /
Record / Table type names, the BatchKey's key element type, and the PK column
list.

### Sibling shape after dedup

Each sibling reduces to: declare local class names → call helper → emit its
own projection (`selectFields` / extra columns) → emit its own join
composition (terminal + bridge joins, or single ON, or window-function
envelope) → emit `WHERE` → emit scatter call → return `MethodSpec`. Target
size: ~50–80 lines per sibling (down from 150–200).

### Hard choices

1. **Mutate the body builder vs. return a CodeBlock.** Mutation. The
   prelude is a sequence of `body.addStatement` / `body.add` calls that the
   sibling continues building from; threading a returned CodeBlock through
   would force the sibling to re-emit a "pick up where the prelude left off"
   handle. Mutation is the natural fit for builder-pattern code.
2. **Single-cardinality path treatment.** The same helper handles all
   three cardinalities; single-cardinality has `joinPath.size() == 1` so the
   FK-chain loop emits one declaration. The `firstHop` cast already lives in
   each sibling (`(JoinStep.FkJoin) joinPath.get(0)`); lift it into the
   bindings record so callers don't reach into `joinPath` again. Bridging
   aliases beyond `firstAlias` only exist when `path.size() > 1`; single-card
   callers ignore them by reading `aliases.get(0)`.
3. **Helper location.** Private static method + nested record on
   `SplitRowsMethodEmitter`. No new file. Keeps the dedup local; nothing
   else in the codebase needs the prelude shape.
4. **Scope creep.** Do not also collapse the FK-chain `for` loop into a
   helper inside the helper. The five-act prelude is the natural extraction
   boundary; sub-extracting hides the shape of what's emitted. Do not
   collapse the three scatter helpers (`scatterByIdx` /
   `scatterSingleByIdx` / `scatterConnectionByIdx`) — already a non-goal
   below.

### Test coverage

Existing pipeline tests in `SplitTableFieldPipelineTest` (and the
single-cardinality and connection variants) assert emitted method signatures
and shape invariants (idx column, scatter helper gates). No new tests; the
refactor passes iff existing tests stay green plus the byte-identical
generated-sources check below.

**Verification step.** Diff the generated tree before and after:

```
mvn -f graphitron-rewrite/graphitron-rewrite-test/pom.xml clean generate-sources
diff -ru target/generated-sources.before target/generated-sources.after
```

Expected: empty diff. Any non-empty diff is a regression in the dedup, not
a behaviour change to land.

### Risk

Low. The five-act prelude is genuinely identical across the three siblings;
single-cardinality's inline-vs-loop variation collapses cleanly under
`path.size() == 1`. The bindings-record arity is the only design touch —
keep it minimal (only the values multiple siblings re-derive) so it doesn't
become a god-bag.

## Phase 2: audit emitted-helper Table-parameter convention

**Rule to land:** *Helpers that bind column refs to a jOOQ Table instance
always take the Table as a parameter, never declare their own.* Land the
rule in `docs/rewrite-design-principles.md` and reference it with a one-line
class-comment on each emitter that emits Table-bound helpers.

### Audit findings (2026-04-25)

| Emitter | Emitted helper | Verdict |
|---|---|---|
| `TypeFetcherGenerator.buildOrderByHelperMethod` (line 754) | `<fieldName>OrderBy(DataFetchingEnvironment env, <Table> table)` | Compliant — table is parameter |
| `QueryConditionsGenerator` (line 75) | `<fieldName>Condition(<Table> table, DataFetchingEnvironment env)` | Compliant — table is parameter |
| `LookupValuesJoinEmitter` root form (line 139) | `<fieldName>InputRows(DataFetchingEnvironment env, <Table> table)` | Compliant — table is parameter |
| `LookupValuesJoinEmitter` child form (line 179) | `<fieldName>InputRows(SelectedField sf, <Table> table)` | Compliant — table is parameter |
| `InlineLookupTableFieldEmitter` | Threads `sfName` through; emitted helper takes the same `<Table> table` parameter pattern | Compliant |
| `ConnectionHelperClassGenerator.pageRequest` / `encodeCursor` / `decodeCursor` | Operate on `Field<?>` / `List<SortField<?>>` | N/A — not Table-bound |

Every emitted helper that should comply does. The audit closes with no
generator-code changes needed.

### Deliverable

1. Add a *Helper-locality* subsection to
   `graphitron-rewrite/docs/rewrite-design-principles.md` stating the rule,
   with the table above as evidence and `<fieldName>OrderBy` as the
   canonical example.
2. Add a one-line class-level comment on each of the four emitters
   referencing the design-principles section. Keep the comment to one
   line — the design-principles section is the source of truth.

### Test coverage

The existing `TypeFetcherGeneratorTest.orderByArg_helperMethod_takesEnvAndAliasedTableParameters`
and `splitQueryConnection_withDynamicOrderByArg_emitsOrderByHelperAcceptingAliasedTable`
already pin the convention for `<fieldName>OrderBy`. No new tests; the rule
is documentation, not behaviour.

### Risk

None. Documentation-only.

## Phase 3: rejection-message taxonomy

### Scope and concrete shape

Introduce a shared `RejectionKind` enum at
`no.sikt.graphitron.rewrite.RejectionKind` with four values:

- **`INVALID_SCHEMA`** — the combination of directives or types is
  permanently incompatible. Author fix: drop a directive or restructure the
  type. Examples: `@asConnection` + `@lookupKey`; `@asConnection` on
  inline (non-`@splitQuery`) `TableField`; `@service` returning a
  polymorphic type.
- **`AUTHOR_ERROR`** — the schema references something the jOOQ catalog or
  SDL registry doesn't know about. Author fix: correct the reference.
  Examples: unresolvable `@lookupKey` argument, missing FK between tables,
  unknown column in `@field(name:)`, unresolvable `@reference` path.
- **`DEFERRED`** — generator does not yet support this shape. Examples:
  every `NOT_IMPLEMENTED_REASONS` whole-variant stub
  (`MutationInsertTableField`, `QueryInterfaceField`, etc.); `@service`
  parameter shapes still listed in `plan-service-root-fetchers.md`.
- **`INTERNAL_INVARIANT`** — a classifier-level contract was violated in a
  way that should not be reachable from any valid user schema. Examples:
  empty `joinPath` arriving at an emitter that requires a non-empty path;
  `BatchKey.RowKeyed` cast failing.

### Wire-up

Two records gain a `RejectionKind kind` field:

- `GraphitronField.UnclassifiedField`
  (`graphitron-rewrite/.../model/GraphitronField.java:49`) — currently
  `(parentTypeName, name, location, definition, reason)`. New shape:
  `(parentTypeName, name, location, definition, kind, reason)`.
- `ValidationError` (`graphitron-rewrite/.../ValidationError.java:12`) —
  currently `(message, location)`. New shape: `(kind, message, location)`.

Both are records; the constructor arity break is mechanical (52 sites in
`FieldBuilder.java`, 32 sites in `GraphitronSchemaValidator.java`).

### Hard choices

1. **Shared enum, not nested.** One `RejectionKind` lives at package level
   on the rewrite module, referenced by both records. Avoids duplicating a
   four-value enum and makes "every rejection has a kind" the obvious
   reading.
2. **Scope: classifier rejections only.** Do *not* extend `RejectionKind`
   to `BatchKeyField.unsupportedReason`. Those leaves classify
   *successfully* and emit a runtime-throwing stub at codegen time —
   that's a deferred-feature signal, but the channel is already separate
   (`SplitRowsMethodEmitter.unsupportedReason` →
   `BatchKeyField.unsupportedReason()` → runtime stub) and conflating it
   with `UnclassifiedField` rejections would muddy the contract. Open
   question 1 in the original plan resolves to "no, keep separate".
3. **`NOT_IMPLEMENTED_REASONS` is unaffected.** Whole-variant stubs already
   declare themselves "deferred" by their presence in this map; the
   validator promotes them to `ValidationError` with `kind=DEFERRED` at
   that point. The map keeps its `Class → String` shape (no enum-keying);
   only the validator's wrapper is touched.
4. **AUTHOR_ERROR vs INVALID_SCHEMA boundary.** The line:
   - *AUTHOR_ERROR* if the rejection points at a name the author can correct
     by typo-fixing or by adding a missing schema element ("column `foo`
     not in jOOQ table", "no FK between tables").
   - *INVALID_SCHEMA* if the rejection is structural — no fix-by-rename
     repairs it, the author has to drop or replace a directive
     ("`@asConnection` on `@lookupKey` fields is invalid").
   When in doubt, prefer AUTHOR_ERROR; INVALID_SCHEMA is reserved for
   "this combination cannot work, period". Walking through the 52
   FieldBuilder sites and 32 validator sites against this rule is the bulk
   of the work.
5. **Logging: one-line prefix, not structural change.** `GraphitronSchemaValidator`
   today logs each `ValidationError.message`; after the change, format as
   `[<kind>] <message>` — kebab-cased kind prefix
   (`[invalid-schema]`, `[author-error]`, `[deferred]`,
   `[internal-invariant]`). No separate log levels per kind; no new SLF4J
   markers; no change to the Maven plugin's error envelope. Downstream
   tooling that wants the structured signal can still parse the prefix.
6. **Migration cadence: one commit per record.** Land the enum + the
   `UnclassifiedField` arity break + 52 FieldBuilder call-site updates as
   one commit; land the `ValidationError` arity break + 32 validator
   call-site updates as a second commit. Small enough to review each, large
   enough to not split mechanically.
7. **No test-helper rewrite.** Existing tests assert on
   `messages(errors).contains("...")`; after the prefix change they will
   contain `[<kind>]` followed by the same message. Existing assertions
   with `.contains("…")` substring stay green because the substring still
   appears. Add one new helper `assertHasKind(errors, RejectionKind, String
   substring)` and use it in the dozen tests where mis-categorisation is
   possible (the `@asConnection` + `@lookupKey` case in particular needs
   to ratchet to `INVALID_SCHEMA` so it can't drift back to "deferred").
8. **`UnclassifiedField` flow into `ValidationError`.** Currently
   `GraphitronSchemaValidator` walks unclassified fields and folds their
   `reason` into a `ValidationError`. After the change, the kind
   propagates: `ValidationError(unclassified.kind(),
   unclassified.reason(), unclassified.location())`. No re-derivation;
   the classifier is the source of truth for kind.

### Touch points

- `GraphitronField.UnclassifiedField`: arity break (1 file).
- `FieldBuilder.java` (52 sites): each `new UnclassifiedField(...)` call
  gains a `RejectionKind` argument. Walk every site; assign by the rules
  above. Sample categorisations:
  - `referencePath.errorMessage()` (lines 243, 303) → `AUTHOR_ERROR` (path
    didn't resolve).
  - `tfc.error()` (lines 246, 306, etc.) → mostly `AUTHOR_ERROR` (table
    classification couldn't resolve a name).
  - "`@asConnection` on `@lookupKey` fields is invalid" (lines 252–253,
    266–271) → `INVALID_SCHEMA`.
  - "`@service` returning a polymorphic type is not yet supported" (lines
    1468, 1575) → `DEFERRED` (this is a not-yet, not a permanently-no).
  - Conflict-message rejections (`conflict` from `@table` + `@record` etc.,
    lines 1395, 1452) → `INVALID_SCHEMA`.
- `ValidationError`: arity break (1 file).
- `GraphitronSchemaValidator.java` (32 sites): each `new
  ValidationError(...)` gains a kind. Re-survey by the same rules.
- `RejectionKind.java`: new file, 4 enum values, kebab-case
  `displayName()` for the log prefix.
- `GraphitronSchemaValidator` logging path: format one-line prefix.
- Test helpers: new `assertHasKind` in test support, used in ~6–10
  rejection tests.

### Test coverage

Existing rejection-message tests stay green by `.contains` substring.
**Ratchet additions** in the same commit:

1. `assertHasKind(errors, INVALID_SCHEMA, "@asConnection on @lookupKey")` —
   guards against drift to "not yet supported".
2. `assertHasKind(errors, AUTHOR_ERROR, "no FK between")` — guards a
   typo-class typical case.
3. `assertHasKind(errors, DEFERRED, "MutationInsertTableField")` —
   sample whole-variant deferred case.

### Risk

Medium. The constructor-arity break is mechanical but touches 84 call
sites across two files. Triage of "what kind is this?" is the actual work
and the only place where genuine bugs surface (a "not supported" message
that's actually `INVALID_SCHEMA`). Each such fix lands in the same commit
as the categorisation. No emitter-output changes; no behaviour changes
beyond the log-prefix.

## Order + gating

Phases ship in this order under one InReview cycle:

1. **Phase 1** — `SplitRowsMethodEmitter` dedup. One commit. Verified by
   byte-identical generated-sources diff.
2. **Phase 2** — Helper-locality rule. One commit (design-principles
   section + four one-line emitter comments).
3. **Phase 3** — Rejection taxonomy. Two commits:
   3a. `RejectionKind` enum + `UnclassifiedField` arity break + 52
       FieldBuilder sites.
   3b. `ValidationError` arity break + 32 validator sites + log-prefix
       formatter + `assertHasKind` test helper + three ratchet tests.

Each commit leaves the test suite green; no half-shipped intermediate.

## Non-goals

- Collapsing `scatterByIdx` / `scatterSingleByIdx` / `scatterConnectionByIdx`
  into one generic helper. They have different return types
  (`List<List<Record>>` vs `List<Record>` vs `List<ConnectionResult>`) and
  genuinely different per-idx logic.
- Reshaping `ConnectionResult`. The `List<Record>` narrowing landed in
  split-query-connection §1; further changes belong to whichever plan
  motivates them.
- Adding a new machine-readable message format on top of kind. The kind
  enum + log-prefix is sufficient; richer formats are a downstream tooling
  concern.
- Categorising `BatchKeyField.unsupportedReason` runtime stubs. Already
  decided in Phase 3 hard choice 2; runtime stubs stay on their own
  channel.
- Touching `NOT_IMPLEMENTED_REASONS`'s `Class<? extends GraphitronField>
  → String` shape. Validator wraps its lookup with `kind=DEFERRED`; the
  map itself is unchanged.

## Open questions

All resolved. See Phase 3 hard choices 1–8 and Phase 2 audit findings for
the resolutions.
