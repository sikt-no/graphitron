---
id: R58
title: "Lift `UnclassifiedField` / `UnclassifiedType` onto sealed-result shape"
status: Done
bucket: architecture
priority: 5
theme: structural-refactor
depends-on: []
---

# Lift `UnclassifiedField` / `UnclassifiedType` onto sealed-result shape

> Replace the flat `(RejectionKind kind, String reason)` pair on
> [`GraphitronField.UnclassifiedField`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronField.java)
> and [`GraphitronType.UnclassifiedType`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java)
> with a sealed `Rejection` hierarchy whose arms carry the data each rejection class
> actually has, instead of folding everything into a free-form `String reason`.
> Co-closes the
> [`TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java)
> reach-around used by
> [`GraphitronSchemaValidator.validateVariantIsImplemented`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java)
> and the parallel direct-DEFERRED stamps from `FieldBuilder` onto a single shape.
> Drops `RejectionKind.INTERNAL_INVARIANT` outright: its single producer site is
> a defensive `else` branch behind a sealed return type wider than the helper's
> actual range, and the right shape for "the classifier reached an unreachable
> branch" is an `AssertionError`, not a user-facing rejection.

---

## Motivation

`UnclassifiedField` and `UnclassifiedType` carry a `(RejectionKind kind, String
reason)` pair plus, on the field arm, the originating
`GraphQLFieldDefinition`. This is the "enum + shared free-form bag of strings"
shape that
[*Sealed hierarchies over enums for typed information*](../docs/rewrite-design-principles.adoc)
explicitly cautions against: every arm carries a `String` because the union of
each arm's actual data is wider than `String`.

Three concrete consequences on trunk today:

1. **`AUTHOR_ERROR` already encodes a typed structure as prose.** Every
   `AUTHOR_ERROR` rejection that comes out of `BuildContext.candidateHint`
   carries the same shape under the hood: an attempted name, a list of
   candidates the catalog actually has, and a "did you mean" prefix. The
   classifier resolves the candidates by walking the catalog and collapses them
   into a single string at the call site. The validator then re-emits that
   string verbatim. Editor / LSP consumers downstream of the validator (the LSP
   plan, R18) want the candidate list as a list — for autocomplete fix-its —
   and have to re-derive it by parsing the prose back out, or by re-running the
   classifier. Producing a typed shape at the rejection site is strictly
   cheaper.

2. **`DEFERRED` lives on two axes that don't talk to each other.** The
   classifier produces some `DEFERRED` rejections inline at the call site
   (`FieldBuilder.java` lines 389, 2090, 2468, 2473, 2516, 2523, 2537 — every
   one of them constructs an `UnclassifiedField` with a hand-rolled message
   containing a roadmap-file path), and other `DEFERRED` rejections come out
   of the validator's
   `validateVariantIsImplemented` cross-check against
   `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` (the entries at
   `TypeFetcherGenerator.java:235`+). The shape is the same in spirit — "this
   variant is recognised but not yet emittable, see this roadmap item" — but
   the validator path keys on `field.getClass()` while the inline path keys on
   site identity, and the slug + reason are stamped twice in two different
   formats. R3's "Generator stubs" surface (the validator's gate against the
   `NOT_IMPLEMENTED_REASONS` map) was originally filed as a sub-item of R3 but
   never lifted; this is its umbrella.

3. **`INTERNAL_INVARIANT` is a defensive `else` against a sealed return type
   wider than its caller actually produces.** The single trunk site
   ([`FieldBuilder.java:482`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java))
   appears inside `classifyChildFieldOnTableType`'s nested-fields loop. The
   recursive call returns `GraphitronField` (`permits RootField | ChildField |
   InputField | UnclassifiedField`); the loop handles `UnclassifiedField` and
   `ChildField` explicitly and falls through to `INTERNAL_INVARIANT` for the
   two arms (`RootField`, `InputField`) the helper never produces by
   construction. Surfacing that fallthrough through the `UnclassifiedField`
   validator path puts a "you can't fix this; file a generator bug" message in
   the same compiler-style log line as a typo. The honest fix is an
   `AssertionError` at the unreachable branch, not a user-facing rejection
   class — see Phase 0.

`InvalidSchema` is the smallest of the three: today the messages embed
directive *names* as bare strings ("`@asConnection` on inline (non-`@splitQuery`)
TableField is not supported"). A future lift could carry directive identifiers
as a typed value type the same way `BuildContext` does internally for directive
lookup, but that is heavy machinery for a minority arm with no current
downstream consumer demanding typed identifiers; keeping the directive list as
`List<String>` for now and lifting to a typed identifier in a follow-up if LSP
fix-its or watch-mode tooling need it is the right cardinality. The structural
benefit of `InvalidSchema` as a sealed sub-hierarchy still applies (typed
`DirectiveConflict` vs `Structural` arms), it's just that the directive
identifiers themselves stay as strings until there's a consumer for the typed
shape.

### Relationship to R3 (classification-vocabulary follow-ups)

R3 §2 ("emit a build warning for `@splitQuery` on a result-mapped parent")
shipped, taking with it the warnings channel
([`BuildWarning`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildWarning.java)
and `BuildContext.addWarning`). The remaining R3 items are doc audits and the
condition-method signature work; they neither block nor are blocked by this
plan. R3 stays scoped to vocabulary cleanups; R58 is the structural lift this
project hadn't yet filed.

### Relationship to R6 (decompose-fieldbuilder)

R6 set the precedent: lift each cross-cutting concern into a sealed `Resolved`
hierarchy with `Success` / `Rejected` arms; rejections carry the structural
reason data, not free-form prose. Those resolvers already produce typed
rejection records (`LookupKeyDirectiveResolver.Resolved.Rejected`,
`OrderByResolver.Resolved.Rejected`, etc.) which currently get re-flattened at
the boundary into `UnclassifiedField(kind, reason)`. The R6 result of "every
classifier concern returns a typed sealed result" stops at `FieldBuilder`'s
output; this plan extends that shape across the boundary so the validator and
downstream consumers also see typed data.

### Relationship to R1 (`BatchKey` lifter directive)

R1's `BatchKey` axis split (`ParentKeyed` / `RecordParentBatchKey` sub-interfaces
splitting the variant axis after `LifterRowKeyed` was added) is the structural
analogue. R1 took a single sealed interface (`BatchKey`) where every variant
carried a `keyColumns()` accessor whose semantics were variant-dependent, and
split it into sub-interfaces where each sub-interface's accessors apply
uniformly. R58 does the same lift on the rejection axis: a single
`(RejectionKind, String)` pair where the meaning of `reason` depends on `kind`
becomes a sealed `Rejection` hierarchy where each arm's components apply
uniformly to that arm.

---

## Surface

### Sealed `Rejection` hierarchy

New file
`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/Rejection.java`:

```java
package no.sikt.graphitron.rewrite.model;

import java.util.List;

/**
 * Why a classifier failed to produce a model variant. The four arms carry the
 * structural data each rejection class actually has; the validator and any
 * downstream consumer (LSP fix-its, watch-mode formatter) switch on the variant
 * rather than parsing prose.
 *
 * <p>{@code message()} renders the variant's data as a single human-readable
 * sentence, for the build-time log surface that
 * {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} writes today.
 * Each leaf record overrides it; the interface itself only declares the
 * contract.
 */
public sealed interface Rejection {

    /** Single-sentence form for compiler-style log output. */
    String message();

    /**
     * Author-correctable. Two sub-arms because the data shapes diverge: most
     * AUTHOR_ERROR sites today are structural rule violations carrying just
     * prose; a minority resolve a name against a closed set and carry the
     * lookup attempt + candidates. Forcing every site to construct an empty
     * {@code candidates} list to fit a single arm violates *Sealed
     * hierarchies over enums for typed information* on the rejection axis.
     * Mirrors R1's split of {@code BatchKey} into {@code ParentKeyed} /
     * {@code RecordParentBatchKey}: each sub-arm's accessors apply uniformly
     * to that arm.
     */
    sealed interface AuthorError extends Rejection {
        /**
         * The classifier resolved a name (column, table, FK, service method,
         * NodeId key, lifter method, …) that the catalog or SDL registry did
         * not recognise. {@code attempt} is what the author wrote;
         * {@code candidates} is the closed set the catalog had at this site;
         * {@code attemptKind} names the lookup space for the rendered
         * message and for LSP fix-its.
         *
         * <p>Successor of {@link no.sikt.graphitron.rewrite.BuildContext#candidateHint}
         * call sites (~19 across {@code BuildContext}, {@code FieldBuilder},
         * {@code TypeBuilder}, {@code ServiceCatalog},
         * {@code BatchKeyLifterDirectiveResolver}, {@code EnumMappingResolver}):
         * the message renders the same "; did you mean: …" suffix the helper
         * produces today, but the candidate list survives as a list for
         * downstream tooling.
         */
        record UnknownName(
            String summary,
            AttemptKind attemptKind,
            String attempt,
            List<String> candidates
        ) implements AuthorError {
            @Override public String message() { /* renders summary + did-you-mean */ }
        }

        /**
         * Author-correctable structural rule violations that don't resolve a
         * name against a closed set. The majority arm: ~80+ sites today
         * (e.g. "@reference path is required", "paginated fields must have
         * ordering", "@service on a child field requires a Sources parameter").
         */
        record Structural(String reason) implements AuthorError {
            @Override public String message() { return reason; }
        }
    }

    /**
     * Structural rule violations the author can't fix without dropping or
     * replacing a directive: "this combination cannot work, period". Two
     * sub-arms by the same logic as {@link AuthorError}: a minority of sites
     * are directive-conflict ("@X conflicts with @Y", "@asConnection on
     * inline (non-@splitQuery) TableField"), the majority are structural
     * rules without a clean directive enumeration ("lookup fields must not
     * return a connection", "result type does not match input cardinality").
     */
    sealed interface InvalidSchema extends Rejection {
        /**
         * Two or more directives co-occur on the same declaration in a
         * combination the classifier rejects, or one directive is rejected
         * because of where it appears. {@code directives} carries the bare
         * directive names (no leading {@code @}) the rendered message
         * re-prefixes; the names are stored as {@code String} for now,
         * because no consumer downstream of this hierarchy distinguishes
         * directive identifiers from arbitrary prose tokens. If LSP fix-its
         * or watch-mode tooling later demand a typed directive identifier,
         * the field type lifts to a value record in a follow-up; the arm's
         * shape is otherwise stable.
         */
        record DirectiveConflict(
            List<String> directives,
            String reason
        ) implements InvalidSchema {
            @Override public String message() { /* renders reason; directives carried for downstream tooling */ }
        }

        /** Structural-rule majority: ~20 sites; just prose. */
        record Structural(String reason) implements InvalidSchema {
            @Override public String message() { return reason; }
        }
    }

    /**
     * Recognised but not yet generator-supported. {@code planSlug} names the
     * roadmap file under {@code graphitron-rewrite/roadmap/} (no extension);
     * {@code stubKey} names the variant class or other anchor inside that
     * plan. The render form embeds the plan path verbatim ("see
     * graphitron-rewrite/roadmap/&lt;slug&gt;.md") so today's log surface is
     * unchanged; LSP fix-its read the slug as a typed value and offer
     * "open the roadmap item" instead of parsing the path back out.
     */
    record Deferred(
        String summary,
        String planSlug,
        StubKey stubKey
    ) implements Rejection {
        @Override public String message() { /* renders summary + roadmap path */ }
    }

    enum AttemptKind {
        COLUMN, TABLE, FOREIGN_KEY, SERVICE_METHOD, TABLE_METHOD, LIFTER_METHOD,
        ENUM_CONSTANT, TYPE_NAME, NODEID_KEY_COLUMN, ARGUMENT_NAME, FIELD_NAME,
        DML_KIND
    }

    /**
     * Stable identifier for a deferred-stub site. Two arms: a variant-class
     * key (the {@code NOT_IMPLEMENTED_REASONS} key form, used when the
     * generator stubs an entire variant class) and an emit-block key (a
     * typed enum, used when a variant classifies cleanly but a particular
     * shape inside the emitter doesn't yet emit). Both forms surface with
     * the same plan-slug rendering; the variant-class form stays compatible
     * with today's validator gate.
     */
    sealed interface StubKey {
        record VariantClass(Class<? extends GraphitronField> fieldClass) implements StubKey {}
        record EmitBlock(EmitBlockReason reason) implements StubKey {}
    }

    /**
     * Closed set of intra-emitter "this shape can't emit yet" reasons. One
     * value per {@code SplitRowsMethodEmitter.unsupportedReason} arm today;
     * a new value lands when a new emit-block predicate is introduced. Pure
     * enum form rather than {@code String}-tagged record because the set is
     * closed and small, and a typo on a new value should be a compile error,
     * not a free-form string drifting from the rendered prose.
     */
    enum EmitBlockReason {
        SPLIT_TABLE_FIELD_CONDITION_JOIN_STEP,
        SPLIT_LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP,
        RECORD_TABLE_FIELD_CONDITION_JOIN_STEP,
        RECORD_LOOKUP_TABLE_FIELD_CONDITION_JOIN_STEP
    }
}
```

Top-level permits are exhaustive: `AuthorError`, `InvalidSchema`, `Deferred`.
Each of `AuthorError` and `InvalidSchema` is itself sealed with two leaf
arms (`UnknownName` / `Structural` and `DirectiveConflict` / `Structural`
respectively). There is no `INTERNAL_INVARIANT` arm: the single trunk site
that produces today's `RejectionKind.INTERNAL_INVARIANT` is a defensive
fallthrough that becomes an `AssertionError` (see Phase 0).

### `UnclassifiedField` / `UnclassifiedType` carry a `Rejection` instead of a pair

`GraphitronField.UnclassifiedField`:

```java
record UnclassifiedField(
    String parentTypeName,
    String name,
    SourceLocation location,
    GraphQLFieldDefinition definition,
    Rejection rejection                          // replaces (RejectionKind kind, String reason)
) implements GraphitronField {
    /** Convenience for log formatters that don't need the structured data. */
    public String reason() { return rejection.message(); }
}
```

`GraphitronType.UnclassifiedType`:

```java
record UnclassifiedType(
    String name,
    SourceLocation location,
    Rejection rejection                          // replaces String reason (always AUTHOR_ERROR-shaped today)
) implements GraphitronType {
    public String reason() { return rejection.message(); }
}
```

`UnclassifiedType` does not carry a `RejectionKind` field today (its sites all
flow through `validateUnclassifiedType` which hardcodes
`RejectionKind.AUTHOR_ERROR` for the validator output — see
[`GraphitronSchemaValidator.java:871`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java)).
Lifting it onto `Rejection` makes the kind explicit at the construction
site instead of asserted at the validator. None of today's `TypeBuilder` sites
produce anything other than `AuthorError` or `Deferred` for an unclassified
type; the validator switch picks up the variant rather than imposing a default.

### `RejectionKind` shrinks to three values

`RejectionKind.INTERNAL_INVARIANT` is removed. The remaining three values
(`AUTHOR_ERROR`, `INVALID_SCHEMA`, `DEFERRED`) are derived from the `Rejection`
variant rather than carried alongside it; `RejectionKind` becomes the
projection layer the
[`ValidationError`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/ValidationError.java)
record uses to produce its kebab-case log prefix:

```java
public enum RejectionKind {
    AUTHOR_ERROR, INVALID_SCHEMA, DEFERRED;

    public static RejectionKind of(Rejection r) {
        return switch (r) {
            case Rejection.AuthorError ignored   -> AUTHOR_ERROR;
            case Rejection.InvalidSchema ignored -> INVALID_SCHEMA;
            case Rejection.Deferred ignored      -> DEFERRED;
        };
    }
    public String displayName() { return name().toLowerCase().replace('_', '-'); }
}
```

The switch is exhaustive at the top-level sealed interface; `AuthorError`'s
internal sub-arms are transparent to the projection (both project to
`AUTHOR_ERROR`). When a future kind is added at the top level, every
`switch (rejection)` site that doesn't handle it is a compile error — the
load-bearing-guarantee shape from the design principles, applied to the
rejection axis.

`ValidationError` keeps its current shape (it is the formatter's view, not the
classifier's).

### Resolver-side `Resolved.Rejected` carries `Rejection`

Trunk has thirteen `*Resolver.java` files
(`ls graphitron/.../*Resolver.java | wc -l`); ten produce rejection arms and
three are total projections. Together with `FieldBuilder`'s internal
`TableFieldComponents.Rejected`, that's eleven rejection-producing types in
total. Each declares either a `Resolved.Rejected(RejectionKind kind, String message)`
arm or a sibling result type with a rejection arm projected to `AUTHOR_ERROR`
at the boundary:

- `ServiceDirectiveResolver`, `TableMethodDirectiveResolver`,
  `ExternalFieldDirectiveResolver`, `LookupKeyDirectiveResolver`
  (directive resolvers; each has a `Resolved.Rejected(RejectionKind, String)`).
- `OrderByResolver`, `ConditionResolver`,
  `MutationInputResolver` (projection resolvers; `ConditionResolver` has two
  sealed result types, both with their own Rejected arm).
- `EnumMappingResolver` (sibling shape: returns sealed `EnumValidation` with
  arms `NotEnum` / `Valid` / `Mismatch`; the lift target is `Mismatch`, which
  becomes `Mismatch(Rejection)` so the caller threads typed rejections out
  rather than re-wrapping a prose string).
- `BatchKeyLifterDirectiveResolver` (R1's resolver, sibling to the R6 set).
- `NodeIdLeafResolver` (R40-introduced sibling, sealed `Resolved.Rejected(String message)`
  with six construction sites; the consumer in `FieldBuilder` projects it to
  `AUTHOR_ERROR` at the boundary).
- `FieldBuilder`'s internal `TableFieldComponents.Rejected` (six sites
  consume it; not a resolver but the same boundary).

`InputFieldResolver`, `LookupMappingResolver`, and `PaginationResolver` are total
projections with no rejection arm and stay as-is.

Every consumer in `FieldBuilder` reads `r.kind()` and `r.message()` and
rebuilds an `UnclassifiedField` with those two fields. The rebuild is
mechanical and lossy: a typed `UnknownName` rejection produced by
`EnumMappingResolver` gets projected to a `(RejectionKind, String)` pair,
then back into a fresh `Rejection.AuthorError.Structural` by the
consumer — the typed candidate list is dropped at the boundary unless the
consumer happens to re-derive it.

The lift: each `Resolved.Rejected` arm is rewritten as
`Resolved.Rejected(Rejection rejection)`. Consumers thread the rejection
through to the `UnclassifiedField` directly:

```java
// Today:
case LookupKeyDirectiveResolver.Resolved.Rejected r ->
    new UnclassifiedField(parentTypeName, name, location, fieldDef, r.kind(), r.message());

// After:
case LookupKeyDirectiveResolver.Resolved.Rejected r ->
    new UnclassifiedField(parentTypeName, name, location, fieldDef, r.rejection());
```

This is what makes the typed `candidates` lift load-bearing in practice:
without the resolver-side lift, every resolver-produced `AuthorError.UnknownName`
would be flattened back to `Structural` at the FieldBuilder boundary, and the
candidate list would survive only at sites where FieldBuilder constructs the
rejection itself. With the lift, a candidate list constructed inside any
resolver flows out through the same untyped channel.

`MutationInputResolver`'s `DmlKindResult.Unknown(String raw)` (single
component on trunk; `MutationInputResolver.java:110`) carries the raw
`typeName` argument and lets the caller stamp the rejection prose. The lift
extends the arm to `Unknown(String raw, List<String> candidates)` (or
equivalent factory call producing the rejection at construction); the caller
threads it into a `Rejection.AuthorError.UnknownName(summary, AttemptKind.DML_KIND,
raw, candidates)`. The candidate list comes from the `DmlKind` enum the R6
review-driven tightening introduced; it's already a clean closed set, but
gets stringified at the call site today.

### Construction ergonomics

`Rejection` ships with one static factory per common shape so call sites
read clearly without naming the sub-arm:

```java
public static Rejection unknownColumn(String attempt, List<String> candidates) {
    return new AuthorError.UnknownName(
        "column '" + attempt + "' could not be resolved",
        AttemptKind.COLUMN, attempt, candidates);
}
public static Rejection structural(String reason)            { return new AuthorError.Structural(reason); }
public static Rejection invalidSchema(String reason)         { return new InvalidSchema.Structural(reason); }
public static Rejection deferred(String summary, String planSlug, Class<? extends GraphitronField> fieldClass) {
    return new Deferred(summary, planSlug, new StubKey.VariantClass(fieldClass));
}
```

Factories for the candidateHint-using sites match the helper's parameter
list 1:1, so `BuildContext.candidateHint(attempt, candidates)` becomes
`Rejection.unknownColumn(attempt, candidates)` (or the matching
`unknownTable` / `unknownForeignKey` / …) at the call site; no arm name
appears in the migrated callsite code. The candidate-hint sites are the
load-bearing ergonomics target; structural sites land via
`Rejection.structural(prose)` and `Rejection.invalidSchema(prose)` and
read identically to today.

The factories are small and live next to the arm definitions so the
sub-arm shapes don't bleed into every call site.

### `INTERNAL_INVARIANT` site becomes an `AssertionError`

The single trunk site at
[`FieldBuilder.java:482`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)
sits inside `classifyChildFieldOnTableType`'s nested-fields loop:

```java
for (var nestedDef : graphQLObjectType.getFieldDefinitions()) {
    var nested = classifyChildFieldOnTableType(...);
    if (nested instanceof UnclassifiedField unc) { return rewrap(unc); }
    if (nested instanceof ChildField cf) {
        nestedFields.add(cf);
    } else {
        // INTERNAL_INVARIANT today — defensive fallthrough for RootField /
        // InputField, which the recursive helper never produces.
        return new UnclassifiedField(..., RejectionKind.INTERNAL_INVARIANT, ...);
    }
}
```

The `else` branch is unreachable by construction: `classifyChildFieldOnTableType`
only emits `ChildField` and `UnclassifiedField`. The fix is to throw rather
than fabricate a fake field rejection:

```java
} else {
    throw new AssertionError(
        "classifyChildFieldOnTableType returned "
        + nested.getClass().getSimpleName()
        + " for nested type '" + elementTypeName
        + "' field '" + nestedDef.getName() + "'");
}
```

Existing tests that referenced `"internal-invariant"` prose (none on trunk
today; verified by `grep -r internal-invariant graphitron-rewrite/graphitron/src/test`)
need no adaptation.

---

## Phase 0 — Drop `INTERNAL_INVARIANT` — shipped

The single producer site (`FieldBuilder.java:492` after R59 line drift)
became an `AssertionError`; `RejectionKind.INTERNAL_INVARIANT` is gone
and the enum is the closed three-value set. Net diff was ~10 lines split
across `FieldBuilder.java` and `RejectionKind.java` (with Javadoc churn).
The body of the original Phase 0 motivation lives in the "Surface" section
above — keep the AssertionError discussion under "`INTERNAL_INVARIANT`
site becomes an `AssertionError`" as the historical record of the change.

---

## Phase A — Sealed `Rejection` plumbing, `UnclassifiedField` lift — shipped

`Rejection.java` landed (top-level sealed `AuthorError | InvalidSchema | Deferred`,
sub-sealed `AuthorError.UnknownName / Structural` and `InvalidSchema.DirectiveConflict / Structural`,
`StubKey.VariantClass | EmitBlock | None`, `EmitBlockReason` enum, plus factory statics
and a self-contained `candidateHint` renderer mirroring `BuildContext.candidateHint`'s
Levenshtein sort). `UnclassifiedField` now carries `Rejection rejection` instead of
the `(RejectionKind kind, String reason)` pair, with `kind()` and `reason()` retained
as convenience accessors that delegate through `RejectionKind.of` / `Rejection.message`.

Every rejection-producing resolver's rejection arm carries `Rejection`:
`BatchKeyLifterDirectiveResolver`, `ServiceDirectiveResolver`, `TableMethodDirectiveResolver`,
`LookupKeyDirectiveResolver`, `ExternalFieldDirectiveResolver` (their
`Resolved.Rejected(RejectionKind, String)` widened), plus `NodeIdLeafResolver`,
`OrderByResolver`, `MutationInputResolver`, `ConditionResolver` (twin
`ArgConditionResult.Rejected` / `FieldConditionResult.Rejected` arms), and
`EnumMappingResolver.EnumValidation.Mismatch` — all now carry `Rejection rejection`.
`FieldBuilder.TableFieldComponents.Rejected` got the same lift. Every site in
`FieldBuilder` that previously built a fresh `UnclassifiedField(..., r.kind(), r.message())`
now passes `r.rejection()` straight through, threading typed rejections across the
resolver / classifier boundary.

`StubKey.None` was added beyond the original spec to accommodate inline-`Deferred`
sites that don't have a clean variant-class anchor (Subscription rejection,
`@record returning polymorphic`, and `@service on @record` parent — the last of
which keeps its `service-record-field` slug via the new `Rejection.deferredAt`
factory). The other inline `Deferred` sites that do map to a variant class
(`Single-cardinality @splitQuery` → `ChildField.SplitTableField.class`) use the
spec's original `Rejection.deferred(summary, fieldClass)` factory.

`GraphitronSchemaValidator.validateUnclassifiedField` projects
`field.rejection()` through `RejectionKind.of` for the kebab-case log prefix and
calls `field.rejection().message()` for the prose tail. Pipeline tier is byte-stable.

`UnclassifiedFieldValidationTest` was adapted to construct a `Rejection.structural`
rather than a `(RejectionKind.AUTHOR_ERROR, String)` pair; expected prose
unchanged. New unit tests `RejectionRenderingTest` (10 cases, every arm) and
`RejectionKindProjectionTest` (totality of `RejectionKind.of` over the sealed
permits + `displayName` kebab-case) verify the lift's contract.

The nested-rewrap site at
[`FieldBuilder.classifyChildFieldOnTableType`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java)
preserves the inner rejection's variant via a `switch` on `unc.rejection()` —
deferred slugs and stub keys are threaded through the wrap; LSP-fix-it
candidate-list threading remains out-of-scope per the existing call-out.

### Phase A deviations from the original spec

- `StubKey.None` arm added (not in the original sealed permits) for inline
  `Deferred` sites with no variant-class anchor. Phase C can re-evaluate; for
  now it makes the `switch (stubKey)` exhaustive without forcing every inline
  site to invent a meaningless `VariantClass(...)` placeholder.
- `Rejection`'s `candidateHint` renderer is inlined into `Rejection.java`
  (private helpers) rather than calling `BuildContext.candidateHint`, because
  `BuildContext` is package-private. Both helpers share the same sort algorithm
  and "; did you mean: " prefix; convergence is enforced by
  `RejectionRenderingTest.authorErrorUnknownNameWithCandidatesAppendsHint`.
- The full typed `UnknownName` lift on every `candidateHint(...)`-using site
  did not land in this phase: most call sites still construct an
  `AuthorError.Structural` with the pre-rendered hint embedded into the prose,
  preserving byte stability. The typed `UnknownName` arm and factories are in
  place; opportunistic migration of individual `candidateHint` sites can land
  as a follow-up. The structural lift the spec called load-bearing — typed
  rejections flowing across the resolver / FieldBuilder boundary — did land.

---

## Phase B — `UnclassifiedType` lift — shipped

`GraphitronType.UnclassifiedType` now carries `Rejection rejection` instead of
the free-form `String reason`. The 24 construction sites (21 in `TypeBuilder`,
3 in `schema/federation/EntityResolutionBuilder`) all migrated: three
table-resolution sites (`@table` on object types, single-table interfaces, and
`@table` on input types) lift to `AuthorError.UnknownName` via the new
`Rejection.unknownTable` factory, threading the catalog's
`allTableSqlNames()` through as a typed `candidates` list; the remaining
sites use `Rejection.structural`, matching the same UnknownName-vs-Structural
split Phase A established.

The validator's `validateUnclassifiedType` switch now projects
`type.rejection()` through `RejectionKind.of` for the kebab-case log prefix
(parallel to `validateUnclassifiedField`'s post-Phase-A shape) and reads the
prose tail from `rejection.message()`. The previously-hardcoded
`RejectionKind.AUTHOR_ERROR` is gone; every site is `AuthorError`-shaped today
so the kebab-case prefix is unchanged on the byte-stable log surface, and a
later site that classifies as `InvalidSchema` or `Deferred` automatically
projects through the same path.

`UnclassifiedType.reason()` is retained as a convenience accessor that
delegates to `rejection.message()`, so the ~50 test sites that read
`((UnclassifiedType) t).reason()` (in `GraphitronSchemaBuilderTest`,
`NodeIdPipelineTest`, `EntityResolutionBuilderTest`) need no adaptation.
`UnclassifiedTypeValidationTest` constructs `Rejection.structural` cases
instead of free-form `(name, location, String)` triples; expected prose
unchanged.

---

## Phase C — Collapse `NOT_IMPLEMENTED_REASONS` onto the `Deferred` arm — shipped

`TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` was the validator's secondary "deferred"
channel: a `Map<Class<? extends GraphitronField>, String>` naming variants that classify
cleanly but emit a stub. The map's reason strings were hand-rolled at the same time as
the inline `RejectionKind.DEFERRED` sites in `FieldBuilder`; they shared the same shape
(summary + roadmap path) without sharing a type.

The lift landed:

1. The map is renamed to `TypeFetcherGenerator.STUBBED_VARIANTS` and its value type
   widens from `String` to `Rejection.Deferred`. Each entry carries a `summary`, a
   `planSlug`, and a `StubKey.VariantClass` (the same key the map already keys on,
   lifted into the value for shape uniformity with the inline-`Deferred` sites). A
   private `deferredFor(fieldClass, summary, planSlug)` helper keeps the entry
   declarations terse.
2. `GraphitronSchemaValidator.validateVariantIsImplemented` looks up `STUBBED_VARIANTS`
   and, on hit, threads the `Rejection.Deferred` through a shared `emitDeferredError`
   helper that projects through `RejectionKind.of` for the kebab-case prefix and reads
   the prose tail from `deferred.message()`. The validator no longer has its own
   path for formatting a deferred-variant error; the same `emitDeferredError` helper
   handles both the `STUBBED_VARIANTS` channel and the four
   `SplitRowsMethodEmitter.unsupportedReason` channels.
3. The four `SplitRowsMethodEmitter.unsupportedReason` overloads
   (`SplitTableField`, `SplitLookupTableField`, `RecordTableField`,
   `RecordLookupTableField`) lift from `Optional<String>` to `Optional<Rejection.Deferred>`.
   Each overload now produces a `Deferred` keyed by the matching
   `EmitBlockReason` enum value (one per arm — all four on the condition-join-step
   shape today) via a shared private `emitBlock(reason, summary)` helper. The four
   `buildFor*` callers strip the prose tail off via `stubReason.get().message()` to
   pass into `buildRuntimeStub`, so the runtime stub's `UnsupportedOperationException`
   message stays byte-stable.
4. `TypeFetcherGenerator.stub(GraphitronField)` reads from `STUBBED_VARIANTS` and
   threads the looked-up `Rejection.Deferred.message()` into the generated
   `UnsupportedOperationException` constructor — the runtime stub message remains
   byte-stable for every stubbed variant.

Net effect: the validator has one path for "deferred", whether the source is the
stubbed-variant map, the inline-deferred classifier site, or the intra-variant
`SplitRowsMethodEmitter` predicate. All three produce `Rejection.Deferred` and
project through `RejectionKind.of` identically.

`GeneratorCoverageTest`, `FieldValidationTestHelper.stubbedError`, the two
`TypeFetcherGeneratorTest` membership-ratchet tests, and `StubbedVariantPipelineTest`
all rename their `NOT_IMPLEMENTED_REASONS` references in lock-step; the
`stubbedError(...)` helper appends `.message()` so its return form stays consistent.
The `IMPLEMENTED_LEAVES` / `NOT_DISPATCHED_LEAVES` / `PROJECTED_LEAVES` /
`STUBBED_VARIANTS` disjoint-partition test
(`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`) adapts to
the renamed constant; the partition itself is unchanged.

### Phase C prose-drift audit

The validator log surface is byte-stable for five of the six `STUBBED_VARIANTS`
entries (`MutationUpdateTableField`, `MutationUpsertTableField`,
`ColumnReferenceField`, `TableMethodField`, `MultitableReferenceField`) and for all
four `SplitRowsMethodEmitter` arms. One entry drifts: the
`ChildField.CompositeColumnReferenceField` message originally wove the roadmap path
in via `". See graphitron-rewrite/roadmap/<slug>.md"`; the uniform
`Rejection.Deferred.message()` renderer produces `" — see graphitron-rewrite/roadmap/<slug>.md"`
instead. The drift is purely the separator between the body prose and the roadmap
path (`". See"` → `" — see"`). No test asserts on the exact message string for this
variant, and the drift brings the message into line with the renderer used by every
other deferred site (inline classifier sites, `STUBBED_VARIANTS` siblings, and the
intra-emitter `EmitBlock` arm), so the validator's log surface is now uniform across
all three deferred channels.

---

## Validator changes

`GraphitronSchemaValidator.validateField` and `validateType` keep their switch
shape; the only changes are inside the `UnclassifiedField` /
`UnclassifiedType` arms (use `Rejection.message()` / project to
`RejectionKind`) and inside `validateVariantIsImplemented` (read
`STUBBED_VARIANTS` for `Rejection.Deferred` values, projecting to
`ValidationError`).

Cross-cutting checks (`validatePaginationRequiresOrdering`,
`validateNestingParentCompat`, etc.) are unaffected — they construct
`ValidationError` directly without going through `UnclassifiedField`.

---

## Test surface

### New unit tests

- `RejectionRenderingTest`: each arm's `message()` produces the expected
  prose for representative inputs (covers the "log-output stays identical"
  invariant against a captured snapshot of trunk's prose).
- `RejectionKindProjectionTest`: `RejectionKind.of(Rejection)` is total over
  the sealed permits.

### Adapted existing tests

- `UnclassifiedFieldValidationTest` / `UnclassifiedTypeValidationTest`:
  cases construct `Rejection` arms instead of `(kind, reason)` pairs;
  expected prose unchanged.
- `ErrorChannelClassificationTest`,
  `CheckedExceptionClassificationTest`,
  `IdReferenceShimClassificationTest`: same — switch from `(kind, reason)`
  construction to `Rejection.AuthorError(...)` / `.InvalidSchema(...)` /
  `.Deferred(...)`.
- Pipeline tests that assert on `UnclassifiedField` reasons
  (`NodeIdPipelineTest`, `ServiceRootFetcherPipelineTest`,
  `LookupTableFieldPipelineTest`, `MutationDmlNodeIdClassificationTest`):
  unchanged in observable behaviour. If any test reads `.kind()` directly
  off `UnclassifiedField`, it shifts to `RejectionKind.of(field.rejection())`
  or to the variant pattern (`field.rejection() instanceof
  Rejection.Deferred`).

### Pipeline-tier coverage

- A `Rejection.AuthorError` case where the candidate list is non-empty:
  asserts (a) the validator's prose includes the "did you mean" tail
  unchanged from trunk, (b) the underlying `UnclassifiedField` carries the
  candidate list as a `List<String>` (not just in prose). Provides the LSP
  consumer (R18) a typed hook ahead of its own work.
- A `Rejection.Deferred` case from the inline classifier path and one
  routed through `STUBBED_VARIANTS`: assert both produce identical
  `ValidationError` shape on the validator side.

### Compilation / execution tiers

- `mvn install -Plocal-db` clean against a real schema with at least one
  schema element of each rejection class (covered today by the existing
  fixtures; no fixture additions needed).

---

## Out of scope

- Lifting `BuildWarning.message` onto a sealed shape. The warnings channel
  has one production site today (the `@table + @record` directive shadowing
  case in `TypeBuilder`); a sealed lift on a single-arm hierarchy is
  premature. Re-evaluate if R3 §3 audit work or future warnings push the
  count past three.
- Lifting validator-side `ValidationError` construction onto `Rejection`.
  Forty sites in `GraphitronSchemaValidator` (`validateMultiTableParticipants`,
  `validateConnectionType`, `validateColumnReferenceField`, the various
  cardinality / pagination guards, etc.) construct `ValidationError`
  directly without going through `UnclassifiedField`; one site in
  `GraphitronSchemaBuilder.buildRecipeErrors` (federation-recipe rewrap of
  `SchemaProblem`, around line 594) does the same on the builder side.
  Today their kind is asserted at the construction site (always
  `AUTHOR_ERROR` or `INVALID_SCHEMA`); rebuilding them on top of `Rejection`
  would unify the formatter's input shape but is mechanical and not on the
  path of any in-flight item. Files separately as a follow-up; the follow-up
  walks both files, not just the validator.
- LSP fix-its consuming the typed `AuthorError.UnknownName.candidates`.
  R18's plan describes what the LSP will do with classifier output; this
  plan supplies the typed shape it needs but does not also wire the LSP.
- Renaming `RejectionKind` itself (e.g. to `RejectionClass` to avoid the
  enum-of-typed-information shape). With three values and a derived
  projection role, the name reads cleanly enough.
- Migrating away from the singleton `RejectionKind` in `ValidationError`.
  The validator's external contract — emit `(kind, message, location)` —
  is what `ValidateMojo` and watch-mode formatters consume; changing it is
  a separate decision.
- Splitting `Rejection.AuthorError.UnknownName` further by `AttemptKind`.
  The kinds enumerate the lookup spaces but every space carries the same
  `(attempt, candidates)` shape, so a single arm with an enum tag is the
  right cardinality. If a kind grows arm-specific data later, it splits at
  that point under R58's same precedent.
- Lifting `ArgumentRef.UnclassifiedArg.reason: String` and the closer sibling
  `ArgumentRef.ScalarArg.UnboundArg(attemptedColumnName, reason)` onto
  `Rejection`. Both are rejection-carriers on the argument classification axis.
  `UnboundArg` is the more interesting one: it already carries a typed
  `attemptedColumnName` field — the same shape `AuthorError.UnknownName(attempt,
  candidates)` would encode, minus the candidate list. The catalog-side candidate
  set at construction time is in scope (the call site has already walked the target
  table's columns), so the post-R58 follow-up is small. `UnclassifiedArg` is the
  catch-all on the same axis with just prose. Same pattern, separate axis: the
  rejections still flow through `projectFilters` and the field-level error aggregation
  before they reach the validator, which is its own thread of work. Could share the
  same `Rejection` shape but don't have to. Re-evaluate after R58 lands; if the
  follow-up plan happens, walk both records together.
- Threading nested rejection chains through the `nestedFields` rewrap site
  in `FieldBuilder.classifyChildFieldOnTableType` (around line 475). Today
  the inner `UnclassifiedField`'s reason is prefixed with
  `"nested type 'X' field 'Y': "` and re-stamped as a fresh outer
  `UnclassifiedField`, dropping the inner rejection's typed structure.
  With typed rejections we could carry an inner `Rejection` as a child
  component on a new `Rejection.NestedReject(outerSite, inner)` arm. That
  shape would also benefit error-aggregation consumers (LSP, watch-mode)
  but is a non-trivial addition (every consumer that reads `rejection()`
  has to recurse). Out of scope here; reach for it if the prose-prefix
  pattern starts swallowing recoverable structure at more sites.
- Switching the watch-mode delta tracker's key from `(file, coordinate,
  kind, message)` to `(file, coordinate, rejection)`. Today's keying on
  `message` couples the delta tracker to cosmetic prose changes; a typed
  key would survive renderer evolution. Falls out naturally once R58
  lands; not in this plan because the delta tracker lives in the watch-mode
  formatter, which has its own roadmap surface.

---

## Phasing summary

The plan landed in four phases: 0 (the `INTERNAL_INVARIANT` removal), A (the
structural lift and the resolver-side `Resolved.Rejected` lift), B (the mechanical
`UnclassifiedType` mirror), and C (the `STUBBED_VARIANTS` rename and validator-gate
consolidation). Each phase shipped as a single PR; each compiled cleanly on its
own and left the validator's external contract unchanged.

The whole plan is internal-refactor scoped: no user-visible directive, goal, or
output format changes. The validator's log surface stayed byte-stable through
Phases 0, A, and B; Phase C drifted exactly one prose line for the
`CompositeColumnReferenceField` stubbed variant (`". See"` → `" — see"`) by
folding its hand-rolled message onto the uniform `Rejection.Deferred.message()`
renderer — see Phase C's prose-drift audit. The single `INTERNAL_INVARIANT` site
shifted from a user-facing field rejection to an `AssertionError`; that's the only
behavioural change in the entire plan, and it is invisible against any well-formed
schema.
