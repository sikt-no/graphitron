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

    // R266 retired both the @value partition (acceptsValueMarker) and the resolveInput PK-coverage
    // check (requiresPkCoverage): UPDATE (R246/R258) and DELETE (R266) now identify rows through the
    // UpdateRowsWalker / DeleteRowsWalker's catalog-derived PK-or-UK match, so neither predicate has
    // a caller. INSERT (the lone verb still completing resolveInput) has no WHERE clause to cover.
}
