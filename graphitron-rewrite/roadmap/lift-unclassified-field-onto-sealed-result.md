---
id: R58
title: "Lift `UnclassifiedField` / `UnclassifiedType` onto sealed-result shape"
status: Spec
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
> Lifts `INTERNAL_INVARIANT` off the field hierarchy entirely — it is a generator
> bug, not a field classification, and modelling it as a field rejection conflates
> "your schema is invalid" with "the classifier is broken".

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
   (`FieldBuilder.java` lines 325, 2019, 2418, 2423, 2465, 2472, 2486 — every
   one of them constructs an `UnclassifiedField` with a hand-rolled message
   containing a roadmap-file path), and other `DEFERRED` rejections come out
   of the validator's
   `validateVariantIsImplemented` cross-check against
   `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` (the entries at
   `TypeFetcherGenerator.java:235-256`). The shape is the same in spirit — "this
   variant is recognised but not yet emittable, see this roadmap item" — but
   the validator path keys on `field.getClass()` while the inline path keys on
   site identity, and the slug + reason are stamped twice in two different
   formats. R3's "Generator stubs" surface (the validator's gate against the
   `NOT_IMPLEMENTED_REASONS` map) was originally filed as a sub-item of R3 but
   never lifted; this is its umbrella.

3. **`INTERNAL_INVARIANT` is shoehorned onto the field hierarchy.** A field
   that carries `RejectionKind.INTERNAL_INVARIANT` is not really an
   "unclassified field" at all — it is a classifier bug surfacing as a
   `(GraphQLFieldDefinition, message)` pair routed through the field
   validator. Today there is exactly one site
   ([`FieldBuilder.java:417`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java))
   where the classifier produces this kind, when a nested field's class
   hierarchy doesn't hit any expected variant. Surfacing it through the
   `UnclassifiedField` validator path means a build-time error ends up in the
   same compiler-style log line as a typo, but the user can't fix it — the
   correct response is "file a generator bug". Routing this through the same
   channel as user errors leaks the abstraction.

`InvalidSchema` is the smallest of the three: today the messages embed
directive *names* as bare strings ("`@asConnection` on inline (non-`@splitQuery`)
TableField is not supported"); the natural carriers are directive identifiers
the schema author types into the SDL, lifted to the same `DirectiveRef` /
`DirectiveName` value shape `BuildContext` uses internally for directive
lookup. This is the lowest-leverage of the three lifts but is in scope because
it falls out of the same sealed-hierarchy refactor.

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
 * <p>{@code message()} is a default method that renders the variant's data
 * as a single human-readable sentence, for the build-time log surface that
 * {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} writes today.
 */
public sealed interface Rejection {

    /** Single-sentence form for compiler-style log output. */
    String message();

    /**
     * Author-correctable: the classifier resolved a name (column, table, FK,
     * service method, NodeId key, …) that the catalog or SDL registry did not
     * recognise. {@code attempt} is what the author wrote; {@code candidates}
     * is the closed set the catalog had at this site; {@code attemptKind}
     * names the lookup space ("column", "table", "FK", …) for the rendered
     * message and for LSP fix-its.
     *
     * <p>Successor of {@link no.sikt.graphitron.rewrite.BuildContext#candidateHint}
     * call sites: the message renders the same "; did you mean: …" suffix
     * the helper produces today, but the candidate list survives as a list
     * for downstream tooling.
     */
    record AuthorError(
        String summary,
        AttemptKind attemptKind,
        String attempt,
        List<String> candidates
    ) implements Rejection {
        @Override public String message() { /* renders summary + did-you-mean */ }
    }

    /**
     * Structural: a directive combination cannot work, period. {@code directives}
     * names the directive identifiers (not the prose forms) the classifier
     * inspected at this site; {@code reason} explains the conflict in terms
     * of those identifiers.
     */
    record InvalidSchema(
        List<DirectiveName> directives,
        String reason
    ) implements Rejection {
        @Override public String message() { /* renders reason with @directive identifiers */ }
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
        COLUMN, TABLE, FOREIGN_KEY, SERVICE_METHOD, TABLE_METHOD, ENUM_CONSTANT,
        TYPE_NAME, NODEID_KEY_COLUMN, ARGUMENT_NAME, FIELD_NAME
    }

    /**
     * Stable identifier for a deferred-stub site. Either a variant class
     * (the {@code NOT_IMPLEMENTED_REASONS} key form) or an inline-deferred
     * site keyed by a SemanticKey naming the shape that triggered the defer
     * (e.g. {@code SINGLE_CARDINALITY_MULTI_HOP_SPLIT}). Both forms surface
     * with the same plan-slug rendering; the variant-class form stays
     * compatible with today's validator gate.
     */
    sealed interface StubKey {
        record VariantClass(Class<? extends GraphitronField> fieldClass) implements StubKey {}
        record SemanticKey(String key) implements StubKey {}
    }

    /**
     * A directive identifier as the schema author types it (with leading {@code @}
     * stripped on storage). Sibling shape to the SDL-side identifiers
     * {@code BuildContext} reads when it walks {@code field.getDirective(name)}.
     * Stored as a value type rather than a {@code String} so consumers cannot
     * confuse it with prose.
     */
    record DirectiveName(String name) {
        public DirectiveName {
            if (name.startsWith("@")) throw new IllegalArgumentException(
                "DirectiveName stores the bare identifier; strip the leading '@' at the call site");
        }
        @Override public String toString() { return "@" + name; }
    }
}
```

Sealed permits are exhaustive: `AuthorError`, `InvalidSchema`, `Deferred`. The
`INTERNAL_INVARIANT` arm is **not** a permit — see "Phase D" below for where it
goes instead.

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
[`GraphitronSchemaValidator.java:919`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java)).
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

`ValidationError` keeps its current shape (it is the formatter's view, not the
classifier's).

### `ClassifierBug` channel for what was `INTERNAL_INVARIANT`

New file
`graphitron/src/main/java/no/sikt/graphitron/rewrite/ClassifierBug.java`:

```java
package no.sikt.graphitron.rewrite;

import graphql.language.SourceLocation;

/**
 * A classifier-level invariant violation: a code path the classifier guarantees
 * is unreachable from any valid user schema was reached anyway. Treated as a
 * generator bug, not a user error.
 *
 * <p>Surfaces through {@link GraphitronSchema#classifierBugs()} (parallel to
 * {@code errors()} / {@code warnings()}); {@code ValidateMojo} formats them
 * with a clear "this is a Graphitron bug; please file an issue at …" preamble
 * and a non-zero exit code distinct from a user-error exit. They never become
 * {@link ValidationError}s.
 */
public record ClassifierBug(String coordinate, String message, SourceLocation location) {}
```

The single trunk site
([`FieldBuilder.java:417`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java))
that produces `RejectionKind.INTERNAL_INVARIANT` today shifts to
`ctx.addClassifierBug(new ClassifierBug(...))` and returns a sentinel
`UnclassifiedField` arm whose `Rejection` is shaped as a `Deferred` with a
`SemanticKey` of `"classifier-bug-fallback"`, so the field still flows through
the validator (it isn't classified) but the user-facing message points at the
bug channel rather than presenting itself as a fixable schema error.

`BuildContext` gains an `addClassifierBug(ClassifierBug)` method paralleling
`addWarning(BuildWarning)`. `GraphitronSchema` gains a
`List<ClassifierBug> classifierBugs()` accessor parallel to `warnings()`.

---

## Phase A — Sealed `Rejection` plumbing, `UnclassifiedField` lift

Single-PR scope:

- Add `Rejection.java` (sealed interface + arms + `DirectiveName` + `StubKey`).
- Migrate `UnclassifiedField` to carry `Rejection` instead of `(kind, reason)`.
- Migrate `RejectionKind` to the three-value form with `of(Rejection)`.
- Update every `UnclassifiedField` construction site in `FieldBuilder`,
  `MutationInputResolver`, `LookupKeyDirectiveResolver`, `OrderByResolver`,
  `ServiceDirectiveResolver`, `TableMethodDirectiveResolver`,
  `ExternalFieldDirectiveResolver`, `EnumMappingResolver`,
  `ConditionResolver`, `CheckedExceptionMatcher`, and the input field /
  errors-field paths in `FieldBuilder`. Concrete site list (sorted by file +
  line, generated from `grep -n "new UnclassifiedField" graphitron/src/main`):
  ~50 sites. Each site picks the right `Rejection` arm based on the data it
  already has.
- Update
  [`GraphitronSchemaValidator.validateUnclassifiedField`](../graphitron/src/main/java/no/sikt/graphitron/rewrite/GraphitronSchemaValidator.java)
  to project `field.rejection()` to a `RejectionKind` and surface the
  arm-specific message via `Rejection.message()`. The validator's output bytes
  stay identical (same kebab-case prefix, same prose tail) for every site;
  this is the no-output-change refactor.

The `AuthorError.candidates` list is the load-bearing addition: every
`candidateHint(...)` call site in `FieldBuilder`, `BuildContext`,
`EnumMappingResolver`, `ServiceCatalog` (5 sites total) constructs an
`AuthorError` whose `candidates` list is the same data the helper already
walked — the helper itself goes from "render to string" to "render +
attach to rejection". Net new code is ~10 lines; the helper's internals
stay.

### Renderings (zero log-output drift)

`Rejection.message()` is exact-byte-compatible with today's prose for every
site that has a corresponding rendering test (every site in
`UnclassifiedFieldValidationTest`,
`UnclassifiedTypeValidationTest`, `ErrorChannelClassificationTest`,
`CheckedExceptionClassificationTest`, the message column of every "bad SDL
classifies as UnclassifiedField" pipeline test). Phase A's pipeline diff
should be empty against trunk.

If a site has prose that the new arm cannot reproduce verbatim, the lift
adjusts the arm's renderer to match — the goal is no log churn during the lift.
Cleanup of inconsistent prose belongs in a separate commit, not this one.

### Validator gate paths

`validateUnclassifiedField` becomes:

```java
private void validateUnclassifiedField(UnclassifiedField field, List<ValidationError> errors) {
    errors.add(new ValidationError(
        RejectionKind.of(field.rejection()),
        field.qualifiedName(),
        "Field '" + field.qualifiedName() + "': " + field.rejection().message(),
        field.location()
    ));
}
```

`validateUnclassifiedType` mirrors. The hardcoded
`RejectionKind.AUTHOR_ERROR` at the type validator site is gone — the kind
comes from the rejection.

---

## Phase B — `UnclassifiedType` lift (mechanical)

Same shape as Phase A, on
`TypeBuilder`'s ~22 `new UnclassifiedType(...)` sites
(`grep -n "new UnclassifiedType" graphitron/src/main`). Every site today
produces an `AuthorError`-shaped string ("table 'X' could not be resolved in
the jOOQ catalog", "no `@table` directive on '...'", participant resolution
errors, NodeId key-column errors). A handful of these already have candidate
lists handy (`catalog.allTableNames()`, `catalog.allForeignKeySqlNames()`) —
those become first-class `AuthorError` rejections with populated `candidates`;
the rest carry empty `candidates`.

Phase A and Phase B can ship in the same PR if the diff stays under ~600
lines; otherwise B follows A.

---

## Phase C — Collapse `NOT_IMPLEMENTED_REASONS` onto the `Deferred` arm

`TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS` today is the validator's
secondary "deferred" channel: a `Map<Class<? extends GraphitronField>, String>`
naming variants that classify cleanly but emit a stub. The map's reason
strings are hand-rolled at the same time as the inline `RejectionKind.DEFERRED`
sites in `FieldBuilder`; they share the same shape (summary + roadmap path)
without sharing a type.

The lift:

1. Rename `NOT_IMPLEMENTED_REASONS` to `STUBBED_VARIANTS` and change its
   value type from `String` to `Rejection.Deferred`. Each entry now carries
   a `summary`, `planSlug`, and `StubKey.VariantClass` (the same key the
   map already keys on, lifted into the value for shape uniformity with the
   inline-`Deferred` sites).
2. `validateVariantIsImplemented` looks up `STUBBED_VARIANTS` and, on hit,
   produces a `ValidationError(DEFERRED, …, deferred.message(), …)` —
   structurally identical to a classifier-time `Deferred` rejection projected
   through the validator. The validator stops being the only path that knows
   how to format a deferred-variant error; both paths share `Deferred.message()`.
3. The `unsupportedReason` predicates on `SplitRowsMethodEmitter` (called from
   `validateVariantIsImplemented` for the four split / record-split variants)
   shift their return type from `Optional<String>` to
   `Optional<Rejection.Deferred>`. The `SemanticKey` form of `StubKey` lands
   here: each `unsupportedReason` arm produces a `Deferred` with a stable key
   like `SPLIT_TABLE_FIELD_SINGLE_CARDINALITY_MULTI_HOP` so a downstream
   consumer can distinguish "field is a stubbed leaf class" from "field is
   an emittable leaf class but this particular shape doesn't emit".

Net effect: the validator has one path for "deferred", whether the source is
the stubbed-variant map, the inline-deferred classifier site, or the
intra-variant `SplitRowsMethodEmitter` predicate. All three produce
`Rejection.Deferred` and project through `RejectionKind.of` identically.

The `IMPLEMENTED_LEAVES` / `NOT_DISPATCHED_LEAVES` / `STUBBED_VARIANTS`
disjoint-partition test
(`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`)
adapts to the renamed constant; the partition itself does not change.

---

## Phase D — `ClassifierBug` channel for `INTERNAL_INVARIANT`

Net new pieces:

- `ClassifierBug` record (under `graphitron/src/main/java/no/sikt/graphitron/rewrite/`).
- `BuildContext.addClassifierBug(ClassifierBug)` method + private
  `List<ClassifierBug>` field, paralleling `addWarning`.
- `GraphitronSchema.classifierBugs(): List<ClassifierBug>` accessor + ctor
  parameter, paralleling `warnings()`.
- `ValidateMojo` / `GenerateMojo` consume `classifierBugs()` and emit a
  separate "this is a Graphitron bug" log section before the `errors()`
  section, then non-zero exit with a distinct exit code (or a clear separate
  log preamble — the exit code split is the secondary mechanism, the log
  routing is the primary).

Single trunk site converts:

- `FieldBuilder.java:417`: the nested-variant fallthrough. Today's
  `UnclassifiedField(parentTypeName, name, location, fieldDef,
  RejectionKind.INTERNAL_INVARIANT, "...")` becomes
  `ctx.addClassifierBug(new ClassifierBug(...))` plus an
  `UnclassifiedField` whose rejection is a `Deferred` with
  `StubKey.SemanticKey("classifier-bug-fallback")` — so the field still
  surfaces as a build-time error against the user, but the actual diagnostic
  the developer reads is the `ClassifierBug` entry.

The classifier-bug surface is intentionally narrow: only this single site
exists today, and the goal is to keep it that way. New sites get added as
generator bugs are caught, not as a routine error category.

`RejectionKind` no longer has an `INTERNAL_INVARIANT` value after this
phase. Tests that referenced it (e.g. variant tests with
`"INTERNAL_INVARIANT"` in the prose) shift to assertions over the
`ClassifierBug` channel.

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
- `DirectiveNameTest`: `@`-stripping invariant + `toString()` re-prefixes.
- `ClassifierBugChannelTest`: `BuildContext.addClassifierBug` accumulates;
  `GraphitronSchema.classifierBugs()` exposes the list; the field-fallthrough
  site adds an entry instead of producing an `INTERNAL_INVARIANT`.

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
- Lifting `ValidationError.message` similarly. The validator is the
  formatter; structured data on the rejection side flows *into* the
  formatter, not through it.
- LSP fix-its consuming the typed `AuthorError.candidates`. R18's plan
  describes what the LSP will do with classifier output; this plan supplies
  the typed shape it needs but does not also wire the LSP.
- Renaming `RejectionKind` itself (e.g. to `RejectionClass` to avoid the
  enum-of-typed-information shape). With three values and a derived
  projection role, the name reads cleanly enough.
- Migrating away from the singleton `RejectionKind` in `ValidationError`.
  The validator's external contract — emit `(kind, message, location)` —
  is what `ValidateMojo` and watch-mode formatters consume; changing it is
  a separate decision.
- Splitting `Rejection.AuthorError` further by `AttemptKind`. The kinds
  enumerate the lookup spaces but every space carries the same
  `(attempt, candidates)` shape, so a single arm with an enum tag is the
  right cardinality. If a kind grows arm-specific data later, it splits at
  that point under R58's same precedent.

---

## Phasing summary

Default landing order is A → B → C → D, single PR per phase. A and B are
the structural lift; C is the validator-gate consolidation; D is the
classifier-bug carve-out. Each phase compiles cleanly on its own and leaves
the validator's external contract unchanged.

A and B can collapse into one PR if the diff size allows. C and D each
warrant their own PR — C touches the generator's stub map and the validator's
deferred-gate path together, and D introduces a new public surface
(`ClassifierBug` channel) that wants its own review.

The whole plan is internal-refactor scoped: no user-visible directive,
goal, or output format changes. The validator's log surface is byte-stable
through Phase B; Phase C may slightly normalise prose for the few stubbed
variants whose hand-rolled message diverges from the new uniform renderer
(audit and document any drift in the Phase C commit message); Phase D
introduces a new "classifier bug" log preamble where today there is none,
and that is the only user-visible change in the entire plan.
