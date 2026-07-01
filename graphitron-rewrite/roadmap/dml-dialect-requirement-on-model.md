---
id: R63
title: "Type UPSERT dialect requirement on the model"
status: Ready
bucket: architecture
priority: 7
theme: mutations-errors
depends-on: []
last-updated: 2026-07-01
---

# Type UPSERT dialect requirement on the model

> UPSERT's "not on Oracle" requirement is currently expressed as a free-form
> `postDslGuard` `CodeBlock` threaded into the shared `buildDmlFetcher`. Lift
> it onto the model as typed data so the constraint is discoverable on
> `MutationUpsertTableField`, the verb-neutral skeleton stays verb-neutral,
> and the validator can eventually reject it at validate time once the
> consumer's target dialect is known at codegen time.

Background lives in commit `181c28f` ("R22: append architectural follow-ups
from post-shipping review"); this item carries that lift forward as standalone
work after R22's stub-lift phases shipped.

---

## Motivation

`TypeFetcherGenerator.buildMutationUpsertFetcher` builds a `postDslGuard`
`CodeBlock` inline (`generators/TypeFetcherGenerator.java:3835-3842`):

```java
var postDslGuard = CodeBlock.builder()
    .beginControlFlow("if (dsl.dialect().name().startsWith($S))", "ORACLE")
    .addStatement("throw new $T($S)", UnsupportedOperationException.class,
        "@mutation(typeName: UPSERT) is not supported on Oracle: ...")
    .endControlFlow()
    .build();
```

The shared `buildDmlFetcher` carries a `postDslGuard` `CodeBlock` slot as a
generic pre-DSL guard, threaded through a chain of three overloads
(`generators/TypeFetcherGenerator.java:4313`, `:4330`, `:4369`): a base
overload with neither guard, one adding `postDslGuard`, and the deepest
adding a sibling `postInGuard` (13 params total). Two verbs fill the
`postDslGuard` slot today: UPSERT rejects Oracle (`:3835-3842`, above), and
bulk UPDATE rejects non-PostgreSQL dialects on the `UPDATE ... FROM (VALUES
...)` form (`:3708-3713`). INSERT, DELETE, and single-row UPDATE pass an
empty block. The two guards have *different* semantics: UPSERT rejects a
single named family, bulk UPDATE requires one.

Two smells:

1. *Discoverability.* "UPSERT only emits valid SQL on PostgreSQL" is an
   architectural fact about `MutationUpsertTableField`. Today it is expressed
   only as runtime-emitted Java that performs a string-prefix check on
   `dsl.dialect().name()`. A reader of the model cannot see the constraint;
   the shared skeleton grew an open-ended escape hatch that any future verb
   can fill with arbitrary code, blunting the verb-neutral promise.
2. *Validator can't see it.* Per *Validator mirrors classifier invariants*,
   classifier decisions that imply runtime failure modes should surface at
   validate time. The current shape can't: a free-form `CodeBlock` carries
   no semantic information the validator can read.

## Design

Two new model types under `no.sikt.graphitron.rewrite.model`:

```java
public sealed interface DialectRequirement
        permits DialectRequirement.None,
                DialectRequirement.RequiresFamily,
                DialectRequirement.RejectsFamily {

    record None() implements DialectRequirement {
        public static final None INSTANCE = new None();
    }

    /** Throw unless the request-time dialect is {@code family}. */
    record RequiresFamily(SqlDialectFamily family, String reason)
        implements DialectRequirement {}

    /** Throw when the request-time dialect is {@code family}. */
    record RejectsFamily(SqlDialectFamily family, String reason)
        implements DialectRequirement {}
}

public enum SqlDialectFamily {
    POSTGRES, ORACLE, MYSQL, MSSQL, H2, SQLITE, OTHER;

    /**
     * Maps a jOOQ {@code SQLDialect.name()} to a graphitron dialect family.
     * Name-prefix-based to cover commercial-only dialect enum values that
     * aren't present in the OSS jOOQ distribution (e.g. ORACLE12C,
     * ORACLE19C). Mirrors jOOQ's own {@code SQLDialect.family()} collapse:
     * {@code POSTGRES}, the {@code POSTGRESPLUS} spelling, and
     * {@code YUGABYTEDB} all map to {@code POSTGRES} (cross-check against
     * jOOQ 3.20.11's {@code SQLDialect.family()} membership, the source of
     * truth, rather than guessing). Generated code consults this at request
     * time via the dispatched {@code dsl.dialect().name()} string.
     */
    public static SqlDialectFamily fromDialectName(String name) { ... }
}
```

Sealed-with-`None` (rather than `Optional<DialectRequirement>`) because:

- The principle "Sealed hierarchies over enums for typed information" prefers
  named arms over presence-or-absence.
- The hierarchy ships with three arms (`None`, `RequiresFamily`,
  `RejectsFamily`) because the two live guards have genuinely different
  semantics: UPSERT *rejects* Oracle (jOOQ silently mistranslates `ON
  CONFLICT` to `MERGE INTO`, with semantics drift; other dialects throw their
  own error rather than mistranslate, so only Oracle needs gating), while
  bulk UPDATE *requires* Postgres (the `UPDATE ... FROM (VALUES ...)` form is
  a Postgres extension). Collapsing both into one `RequiresFamily(POSTGRES)`
  arm would silently change UPSERT's reach from "reject Oracle" to "reject
  every non-Postgres dialect", which is a behaviour change, not a refactor.
- Further arms (`RequiresAnyOf(Set<SqlDialectFamily>)`) can land later without
  touching every consumer.
- Pattern-matching reads tighter than a chain of `Optional.ifPresent`.

`SqlDialectFamily` is a graphitron-internal enum (not jOOQ's `SQLDialect`)
because the OSS jOOQ distribution omits commercial-only values like
`ORACLE19C`. The mapping function lives at the boundary, same shape as the
inline name-prefix check today.

`MutationField.DmlTableField` gains the requirement on the sealed supertype.
The interface today declares only `returnExpression()` and `location()` (the
input surface varies by verb: INSERT/UPSERT carry a `TableInputArg`, while
UPDATE/DELETE carry the slim `InputArgRef inputArg` plus a walker carrier, so
there is no shared `tableInputArg()` accessor to extend). The lift adds one
new member:

```java
sealed interface DmlTableField extends MutationField {
    DmlReturnExpression returnExpression();      // existing
    DialectRequirement dialectRequirement();     // new; never null
    SourceLocation location();                   // existing
}
```

Each of the four DML records gains a `DialectRequirement dialectRequirement`
component, set at its construction site. INSERT/UPSERT are built in the shared
`@mutation` switch (`FieldBuilder` ~`:4027` / ~`:4040`); UPDATE is built in
`classifyUpdateTableField` (~`:4105`) and DELETE in `classifyDeleteTableField`
(both intercepted before the shared switch, which throws on the UPDATE/DELETE
arms). The shared `buildDmlField` helper only resolves the return expression
and error channel, then defers record construction to the per-verb builder
lambda; the new component value is supplied by each lambda. Population:

- INSERT/DELETE: `DialectRequirement.None.INSTANCE`.
- UPDATE: `DialectRequirement.None.INSTANCE` for single-row; the bulk arm
  carries `new DialectRequirement.RequiresFamily(SqlDialectFamily.POSTGRES,
  "...UPDATE...FROM (VALUES)...")` because jOOQ silently emulates
  `UPDATE...FROM` on non-Postgres dialects with semantics drift. UPDATE is a
  single record type (`MutationUpdateTableField`) whose bulk-vs-single split is
  the emitter's, driven by `inputArg.list()`; `classifyUpdateTableField` reads
  the same `inputArg.list()` (already resolved at `:4071`) to pick the arm at
  construction. (Bulk DML, R77, has shipped; the
  bulk-UPDATE arm and its inline `postDslGuard` are live at
  `generators/TypeFetcherGenerator.java:3705-3718`.)
- UPSERT: `new DialectRequirement.RejectsFamily(SqlDialectFamily.ORACLE,
  "...UPSERT...MERGE INTO...")`. The reason string carries the actionable
  message that today is hardcoded in the inline `CodeBlock`. UPSERT rejects
  *only* Oracle, not "requires Postgres": the failure mode is jOOQ's silent
  `ON CONFLICT`→`MERGE INTO` mistranslation on Oracle specifically, so H2 /
  MySQL / MSSQL keep today's behaviour (no guard; jOOQ throws its own error
  if it cannot emit `ON CONFLICT`).

Both `postDslGuard` call sites (UPSERT's Oracle gate at `:3835-3842`, bulk
UPDATE's Postgres gate at `:3708-3713`) are live today, and this item lifts
both at once. The `postDslGuard`-parameter removal below covers both.

## Emitter rewrite

`buildDmlFetcher` consults `f.dialectRequirement()` and renders the runtime
guard via a single helper. The `postDslGuard` `CodeBlock` parameter is
replaced by a `DialectRequirement` (always present from the model), which
collapses the three overloads into two (base + `+postInGuard`):

```java
private static MethodSpec buildDmlFetcher(
        TypeFetcherEmissionContext ctx,
        String fetcherName,
        DmlReturnExpression rex,
        Optional<ErrorChannel> errorChannel,
        String inputArgName,
        TableRef tableRef,
        GeneratorUtils.ResolvedTableNames tablesOnly,
        String tableLocal,
        String outputPackage,
        CodeBlock dmlChain,
        DialectRequirement dialectRequirement,   // replaces the postDslGuard CodeBlock slot
        CodeBlock postInGuard,
        boolean listInput) {
    // ...
    builder.addStatement("$T dsl = $L.getDslContext(env)", DSL, ctx.graphitronContextCall());
    emitDialectGuard(builder, dialectRequirement);
    // ...
}

private static void emitDialectGuard(MethodSpec.Builder b, DialectRequirement req) {
    switch (req) {
        case DialectRequirement.None ignored -> { /* no-op */ }
        case DialectRequirement.RequiresFamily r -> {
            b.beginControlFlow("if ($T.fromDialectName(dsl.dialect().name()) != $T.$L)",
                    SqlDialectFamily.class, SqlDialectFamily.class, r.family().name())
             .addStatement("throw new $T($S)",
                    UnsupportedOperationException.class, r.reason())
             .endControlFlow();
        }
        case DialectRequirement.RejectsFamily r -> {
            b.beginControlFlow("if ($T.fromDialectName(dsl.dialect().name()) == $T.$L)",
                    SqlDialectFamily.class, SqlDialectFamily.class, r.family().name())
             .addStatement("throw new $T($S)",
                    UnsupportedOperationException.class, r.reason())
             .endControlFlow();
        }
    }
}
```

Verb-neutral skeleton stays verb-neutral; UPSERT no longer needs special-case
handling at the call site. INSERT/DELETE and single-row UPDATE pass
`DialectRequirement.None` and the helper emits nothing.

The runtime check on the consumer side runs through `SqlDialectFamily`'s
mapping, not a string-prefix check (UPSERT today) or a `family()` call (bulk
UPDATE today). This is the same logic for both, consolidated onto one
boundary mapping that lives once instead of being inlined into emitted Java.

## Tests

Pure model refactor, not a behaviour change: UPSERT still rejects only
Oracle, bulk UPDATE still rejects only non-Postgres. Acceptance gates:

- Existing execution-tier PostgreSQL tests against UPSERT pass unchanged
  (`upsertFilm_updateBranch_writesAndReturnsProjectedFilm`,
  `upsertFilm_insertBranch_writesAndReturnsProjectedFilm` in
  `graphitron-sakila-example/.../querydb/GraphQLQueryTest.java`). The emitted
  guard body changes (now consults `SqlDialectFamily.fromDialectName`), but
  under PostgreSQL the `RejectsFamily(ORACLE)` arm does not fire, exactly as
  today's `startsWith("ORACLE")` check does not fire.
- New unit-tier assertion: `MutationUpsertTableField.dialectRequirement()`
  returns `RejectsFamily(ORACLE, ...)`; INSERT, DELETE, and single-row UPDATE
  return `None.INSTANCE`; the bulk-UPDATE arm returns
  `RequiresFamily(POSTGRES, ...)`.
- New unit-tier assertion on `SqlDialectFamily.fromDialectName`: covers
  `POSTGRES` for any `POSTGRES*` name plus the `POSTGRESPLUS` spelling and
  `YUGABYTEDB` (mirroring jOOQ's `family()` collapse); `ORACLE` for `ORACLE`,
  `ORACLE12C`, `ORACLE19C`, `ORACLE23AI`, etc.; `OTHER` for unrecognised
  names.
- New compilation-tier test: a generated UPSERT fetcher's body throws
  `UnsupportedOperationException` with the message from the model's
  `reason()` slot when invoked under an Oracle-flavoured `DSLContext`. Either
  via a Testcontainers Oracle instance, which is heavy, or via a stub
  `DSLContext` that reports `dialect().name() == "ORACLE19C"`; the latter is
  cheaper and the existing fixture path doesn't include Oracle.

## Implementation sites

- New file `model/DialectRequirement.java`: sealed interface, three arms
  (`None`, `RequiresFamily`, `RejectsFamily`).
- New file `model/SqlDialectFamily.java`: enum + `fromDialectName` mapping
  (mirrors jOOQ `SQLDialect.family()`; cross-check 3.20.11 membership for the
  Postgres-family collapse).
- `model/MutationField.java`: `DmlTableField` interface gains
  `dialectRequirement()`; each of the four DML records gains the component.
- `FieldBuilder`: thread the new component into each per-verb construction
  lambda. INSERT/DELETE get `DialectRequirement.None.INSTANCE`; UPSERT gets
  `RejectsFamily(ORACLE, ...)` (shared `@mutation` switch, ~`:4040`); UPDATE
  gets `None.INSTANCE` for single-row and `RequiresFamily(POSTGRES, ...)` for
  the bulk arm, selected on `inputArg.list()` in `classifyUpdateTableField`
  (~`:4105`). `buildDmlField` itself is verb-generic (resolves return
  expression + error channel only) and is not the population site.
- `generators/TypeFetcherGenerator`:
  - Replace the `postDslGuard` `CodeBlock` parameter with a
    `DialectRequirement` (always present from the model), collapsing the
    three `buildDmlFetcher` overloads (`:4313`, `:4330`, `:4369`) into two:
    base and `+postInGuard`.
  - Add `emitDialectGuard` private helper.
  - `buildDmlFetcher` calls `emitDialectGuard` where it currently emits
    `postDslGuard` (`:4399-4400`).
  - `buildMutationUpsertFetcher` deletes the inline `postDslGuard`
    `CodeBlock` (`:3835-3842`); the call site shrinks toward the shape of
    `buildMutationInsertFetcher` etc.
  - `buildMutationUpdateFetcher`'s bulk arm deletes its inline
    `postDslGuard` (`:3708-3713`) symmetrically.
- `MappingsConstantNameDedup` and any other site that rebuilds DML records:
  thread the new component through.

## Future evolution (out of scope)

- *Validator-time rejection.* When the consumer's configured target dialect
  becomes known at codegen time (a separate plan; today the dialect is a
  request-time fact), the validator can fail the build per *Validator
  mirrors classifier invariants*. Until that lands, the runtime guard
  remains, but it is rendered from typed model data rather than from a
  hand-built `CodeBlock`.
- *Lifting the Oracle restriction.* A separate plan could hand-emit a
  PostgreSQL-equivalent `MERGE INTO` statement on Oracle (rather than relying
  on jOOQ's silent translation) when the dialect family is Oracle. The model
  shape introduced here (`DialectRequirement`) doesn't gate that work; it
  just typifies today's contract.

## Non-goals

- Cross-dialect abstraction over `RETURNING` and `ON CONFLICT`. Graphitron
  targets PostgreSQL; the rewrite does not introduce a dialect translation
  layer.
- Reusing `DialectRequirement` outside `DmlTableField`. The slot is added to
  `DmlTableField` only; promote it to a wider field-set if a second
  `DialectRequirement`-bearing field type appears.
- Typifying `postInGuard`, the sibling free-form `CodeBlock` slot on
  `buildDmlFetcher` (`:4381`). Unlike `postDslGuard`, which carries a
  discoverable model fact ("UPSERT mistranslates on Oracle") that belongs as
  typed data a validator could one day read, `postInGuard` carries imperative
  per-row emission mechanics (building the dynamic SET map, the
  no-settable-fields check, the bulk uniform-shape and duplicate-lookup-key
  guards). There is no `DialectRequirement`-shaped datum hiding in it;
  lifting it would invent a parallel mini-DSL for guard construction, which is
  worse than the `CodeBlock`. It stays a free-form block.
