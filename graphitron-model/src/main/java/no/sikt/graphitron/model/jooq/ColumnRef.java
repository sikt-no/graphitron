package no.sikt.graphitron.model.jooq;


/**
 * A resolved column in a jOOQ table.
 *
 * <p>{@code sqlName} is the SQL column name as it appears in the database (e.g. {@code "film_id"}).
 * {@code javaName} is the Java field name in the jOOQ table class (e.g. {@code "FILM_ID"}).
 * {@code columnClass} is the raw Java class name of the column type as jOOQ reports it via
 * {@code Field.getType().getName()}. For a scalar column this is a source-form FQCN
 * (e.g. {@code "java.lang.Integer"}); for an array-typed column it is the JVM <em>binary</em>
 * descriptor (e.g. {@code "[Ljava.lang.Boolean;"}). Both forms are retained verbatim because both
 * are recoverable: consumers such as {@code EnumMappingResolver} ({@code Class.forName}) and
 * {@code SourceRowDirectiveResolver} ({@code Class.getName()} compares) read the name directly, and
 * a consumer that wants the type as the emit library spells it lifts it through
 * {@code CatalogRefs.columnType}.
 *
 * <p>{@code databaseType} is the Java type of the value jOOQ exchanges with the database: the
 * column converter's {@code fromType().getName()}, equal to {@code columnClass} on a column with no
 * converter. A converter-backed column ({@code bigint} bound to {@code String}) names the type
 * behind the conversion ({@code java.lang.Long}). A node-id decode reads the pair to decide which
 * type a key slot's text is parsed as.
 *
 * <p>Names and no types, deliberately: the store holds a column's binding as a name, so a ref built
 * from a live catalog and a ref read back out of the store are the same value. Deciding how that
 * name is written into a source file is the emitting tier's business and happens there.
 *
 * <p>Used wherever a column reference is needed: output field columns
 * ({@code ChildField.ColumnBackedField},
 * {@code ChildField.ColumnBackedReferenceField}) and
 * {@code @node} key columns ({@code GraphitronType.NodeType}).
 *
 * <p>When a column cannot be resolved the containing field or type is classified as
 * {@code GraphitronField.UnclassifiedField} or
 * {@code GraphitronType.UnclassifiedType} at build time.
 */
public record ColumnRef(String sqlName, String javaName, String columnClass, String databaseType) {

    /**
     * A column with no converter, whose database type is its column class. For test fixtures; a
     * main-source site reads the database type off the catalog, the live {@code Field} or the store.
     */
    public ColumnRef(String sqlName, String javaName, String columnClass) {
        this(sqlName, javaName, columnClass, columnClass);
    }
}
