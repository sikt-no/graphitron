package no.sikt.graphitron.rewrite.model;

/**
 * The four DML statement kinds {@code @mutation(typeName:)} can take. Lifting the schema's
 * raw String into an enum lets every downstream switch (kind dispatch, invariant rules,
 * return-type validation) be exhaustive at compile time.
 *
 * <p>Top-level type in the model package so DML-related model permits (notably
 * {@link MutationField.MutationDmlRecordField}) can carry the kind directly without
 * pulling in {@code MutationInputResolver}, which would invert the rewrite -&gt; model
 * dependency direction the rest of the model observes.
 */
public enum DmlKind {
    INSERT, UPDATE, DELETE, UPSERT;

    /** UPDATE / DELETE / UPSERT require at least one {@code @lookupKey} binding. */
    public boolean requiresLookupKey() {
        return this != INSERT;
    }
    /** UPDATE / DELETE require all PK columns to be covered by {@code @lookupKey} bindings. */
    public boolean requiresPkCoverage() {
        return this == UPDATE || this == DELETE;
    }
}
