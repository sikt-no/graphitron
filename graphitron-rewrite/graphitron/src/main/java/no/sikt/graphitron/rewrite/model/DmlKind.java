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

    /**
     * UPDATE / DELETE require all WHERE-side filter columns to cover the input
     * {@code @table}'s primary key (R144). UPSERT is refused upstream by
     * {@code MutationInputResolver}; INSERT has no WHERE clause to cover.
     */
    public boolean requiresPkCoverage() {
        return this == UPDATE || this == DELETE;
    }

    /**
     * UPDATE accepts {@code @value} on input fields to partition them from the WHERE-side
     * filters. DELETE / INSERT reject {@code @value} as a structural error (DELETE has no
     * assignment clause; INSERT does not partition).
     */
    public boolean acceptsValueMarker() {
        return this == UPDATE;
    }
}
