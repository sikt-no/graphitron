package no.sikt.graphitron.rewrite.field;

import org.jooq.ForeignKey;

/**
 * One resolved step in a {@code @reference} path, corresponding to one {@code ReferenceElement}
 * in the schema.
 *
 * <p>The sealed hierarchy distinguishes four valid states and two error states:
 * <ul>
 *   <li>{@link FkRef} — a jOOQ FK was resolved; no condition.</li>
 *   <li>{@link FkWithConditionRef} — a jOOQ FK was resolved; a condition method was also resolved.</li>
 *   <li>{@link ConditionOnlyRef} — a condition method was resolved; no FK (lift conditions).</li>
 *   <li>{@link UnresolvedKeyRef} — a key name was specified but could not be found in the jOOQ catalog.</li>
 *   <li>{@link UnresolvedConditionRef} — a condition was specified but the method could not be resolved via reflection.</li>
 *   <li>{@link UnresolvedKeyAndConditionRef} — both a key name and a condition were specified, but neither could be resolved.</li>
 * </ul>
 *
 * <p>The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports an error for
 * {@code UnresolvedKeyRef} and {@code UnresolvedConditionRef}. The valid variants are consumed
 * by the code generator.
 */
public sealed interface ReferencePathElementRef
    permits ReferencePathElementRef.FkRef, ReferencePathElementRef.FkWithConditionRef, ReferencePathElementRef.ConditionOnlyRef,
            ReferencePathElementRef.UnresolvedKeyRef, ReferencePathElementRef.UnresolvedConditionRef, ReferencePathElementRef.UnresolvedKeyAndConditionRef {

    /**
     * A {@link ReferencePathElementRef} where a jOOQ {@link ForeignKey} was successfully resolved.
     *
     * <p>{@code key} is the resolved jOOQ FK instance, used at code-generation time to emit
     * {@code .onKey(key)} join clauses. Use {@code key.getName()} to recover the FK constant name.
     */
    record FkRef(ForeignKey<?, ?> key) implements ReferencePathElementRef {}

    /**
     * A {@link ReferencePathElementRef} where both a jOOQ {@link ForeignKey} and a condition method
     * were successfully resolved.
     *
     * <p>{@code key} is the resolved jOOQ FK instance (see {@link FkRef}).
     * {@code condition} is the resolved condition method (see {@link ConditionOnlyRef}).
     */
    record FkWithConditionRef(
        ForeignKey<?, ?> key,
        MethodRef condition
    ) implements ReferencePathElementRef {}

    /**
     * A {@link ReferencePathElementRef} where a condition method was successfully resolved and no
     * jOOQ FK is involved.
     *
     * <p>Used for lift conditions on {@code @service} and {@code @externalField} fields, where the
     * condition method reconnects the result back to the parent table without a FK join.
     *
     * <p>{@code condition} is the resolved condition method; all fields on {@link MethodRef} are
     * guaranteed non-null.
     */
    record ConditionOnlyRef(MethodRef condition) implements ReferencePathElementRef {}

    /**
     * A {@link ReferencePathElementRef} where a key name was specified in the schema but could not be
     * found in the jOOQ catalog.
     *
     * <p>{@code keyName} is the SQL name of the foreign key constant as written in the schema
     * (e.g. {@code "FILM_ACTOR_FK"}). The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator}
     * reports this as an error.
     */
    record UnresolvedKeyRef(String keyName) implements ReferencePathElementRef {}

    /**
     * A {@link ReferencePathElementRef} where a condition method was specified in the schema but could
     * not be resolved via reflection.
     *
     * <p>{@code qualifiedName} is the fully qualified method name as written in the schema
     * (e.g. {@code "com.example.Conditions.activeCustomers"}). The
     * {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports this as an error.
     */
    record UnresolvedConditionRef(String qualifiedName) implements ReferencePathElementRef {}

    /**
     * A {@link ReferencePathElementRef} where both a key name and a condition method were specified in
     * the schema, but neither could be resolved.
     *
     * <p>{@code keyName} is the SQL name of the foreign key constant as written in the schema.
     * {@code conditionName} is the fully qualified condition method name.
     *
     * <p>The {@link no.sikt.graphitron.rewrite.GraphitronSchemaValidator} reports both failures as
     * separate errors — one for the unresolved key and one for the unresolved condition.
     */
    record UnresolvedKeyAndConditionRef(
        String keyName,
        String conditionName
    ) implements ReferencePathElementRef {}
}
