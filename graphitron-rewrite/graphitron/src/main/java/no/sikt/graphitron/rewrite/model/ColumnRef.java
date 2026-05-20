package no.sikt.graphitron.rewrite.model;

/**
 * A resolved column in a jOOQ table.
 *
 * <p>{@code sqlName} is the SQL column name as it appears in the database (e.g. {@code "film_id"}).
 * {@code javaName} is the Java field name in the jOOQ table class (e.g. {@code "FILM_ID"}).
 * {@code columnClass} is the fully qualified Java class name of the column type
 * (e.g. {@code "java.lang.Integer"}).
 *
 * <p>Used wherever a column reference is needed — both for output field columns
 * ({@link no.sikt.graphitron.rewrite.model.ChildField.ColumnField},
 * {@link no.sikt.graphitron.rewrite.model.ChildField.ColumnReferenceField}) and for
 * {@code @node} key columns ({@link no.sikt.graphitron.rewrite.model.GraphitronType.NodeType}).
 *
 * <p>When a column cannot be resolved the containing field or type is classified as
 * {@link no.sikt.graphitron.rewrite.model.GraphitronField.UnclassifiedField} or
 * {@link no.sikt.graphitron.rewrite.model.GraphitronType.UnclassifiedType} at build time.
 *
 * <p>{@code provenance} records whether the SQL column name came from a user-authored
 * {@code @field(name: "...")} argument or was inferred from the SDL field name. R160's
 * LSP inferred-directive arm reads this to render an inlay hint at sites where the user
 * omitted {@code name:}. Most construction sites lift {@code ColumnRef}s from the catalog
 * outside a {@code @field} directive context (PK columns, FK source/target columns, internal
 * order-by columns) and default to {@link NameProvenance.Inferred.FromSdlName}; the LSP
 * projector only reaches {@code ColumnRef}s through field-level carriers
 * ({@code ColumnField}, {@code ColumnReferenceField}, {@code RecordField},
 * {@code PropertyField}, and input-side analogues) and never sees the internal-lift defaults.
 */
public record ColumnRef(String sqlName, String javaName, String columnClass, NameProvenance provenance) {

    /**
     * Back-compat constructor for the dominant case: internal lifters, PK columns,
     * test fixtures, and any other call site where the column was not resolved through
     * a {@code @field(name:)} directive. Defaults provenance to
     * {@link NameProvenance.Inferred.FromSdlName}.
     */
    public ColumnRef(String sqlName, String javaName, String columnClass) {
        this(sqlName, javaName, columnClass, NameProvenance.inferredFromSdlName());
    }

    /** Returns a copy of this ref with the supplied provenance, leaving all other fields untouched. */
    public ColumnRef withProvenance(NameProvenance newProvenance) {
        return new ColumnRef(sqlName, javaName, columnClass, newProvenance);
    }
}
