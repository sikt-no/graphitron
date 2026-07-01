package no.sikt.graphitron.rewrite.model;

/**
 * A typed dialect constraint on a DML mutation field. Lifts the "not on Oracle" / "Postgres only"
 * facts that were previously hand-built {@code postDslGuard} {@code CodeBlock}s in the emitter onto
 * the model, so the constraint is discoverable on {@link MutationField.DmlTableField}, the
 * verb-neutral fetcher skeleton stays verb-neutral, and a validator can eventually reject it at
 * validate time once the consumer's target dialect is known at codegen time.
 *
 * <p>Sealed with an explicit {@link None} arm rather than {@code Optional<DialectRequirement>}: the
 * principle "sealed hierarchies over presence-or-absence" prefers named arms, and the two live
 * guards have genuinely different semantics. UPSERT <em>rejects</em> Oracle (jOOQ silently
 * mistranslates {@code ON CONFLICT} to {@code MERGE INTO} there; other dialects throw their own
 * error rather than mistranslate, so only Oracle needs gating), while bulk UPDATE <em>requires</em>
 * Postgres (the {@code UPDATE ... FROM (VALUES ...)} form is a Postgres extension). Collapsing both
 * into one {@code RequiresFamily(POSTGRES)} arm would silently widen UPSERT's reach from "reject
 * Oracle" to "reject every non-Postgres dialect", which is a behaviour change, not a refactor.
 *
 * <p>Further arms (e.g. {@code RequiresAnyOf(Set<SqlDialectFamily>)}) can land later without
 * touching every consumer.
 */
public sealed interface DialectRequirement
        permits DialectRequirement.None,
                DialectRequirement.RequiresFamily,
                DialectRequirement.RejectsFamily {

    /** No dialect constraint; the verb emits valid SQL on every dialect graphitron targets. */
    record None() implements DialectRequirement {
        public static final None INSTANCE = new None();
    }

    /** Throw at request time unless the dialect is {@code family}; {@code reason} is the message. */
    record RequiresFamily(SqlDialectFamily family, String reason) implements DialectRequirement {}

    /** Throw at request time when the dialect is {@code family}; {@code reason} is the message. */
    record RejectsFamily(SqlDialectFamily family, String reason) implements DialectRequirement {}
}
