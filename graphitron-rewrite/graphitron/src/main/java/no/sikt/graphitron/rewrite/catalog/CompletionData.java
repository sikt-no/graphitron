package no.sikt.graphitron.rewrite.catalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory catalog the LSP queries to answer completion / hover /
 * diagnostic / goto-definition requests. Built by
 * {@link CatalogBuilder} from {@link no.sikt.graphitron.rewrite.JooqCatalog}
 * and the parsed {@link graphql.schema.GraphQLSchema}.
 *
 * <p>Records are immutable; the catalog is rebuilt rather than mutated when
 * the consumer's compiled classpath changes.
 */
public record CompletionData(
    List<Table> tables,
    List<TypeData> types,
    List<ExternalReference> externalReferences,
    Map<String, String> namedReferences,
    Map<String, NodeMetadata> nodeMetadata
) {

    /**
     * Backwards-compatible 4-arg constructor that defaults
     * {@code nodeMetadata} to empty (no {@code @node}-bearing types).
     */
    public CompletionData(
        List<Table> tables,
        List<TypeData> types,
        List<ExternalReference> externalReferences,
        Map<String, String> namedReferences
    ) {
        this(tables, types, externalReferences, namedReferences, Map.of());
    }

    /**
     * Backwards-compatible 3-arg constructor for tests and callers that
     * don't carry a {@code namedReferences} map. Defaults to an empty
     * map (no legacy {@code name:} resolution available).
     */
    public CompletionData(
        List<Table> tables,
        List<TypeData> types,
        List<ExternalReference> externalReferences
    ) {
        this(tables, types, externalReferences, Map.of(), Map.of());
    }

    public static CompletionData empty() {
        return new CompletionData(List.of(), List.of(), List.of(), Map.of(), Map.of());
    }

    public Optional<Table> getTable(String name) {
        return tables.stream()
            .filter(t -> t.name().equalsIgnoreCase(name))
            .findFirst();
    }

    public Optional<TypeData> getType(String name) {
        return types.stream()
            .filter(t -> t.name().equals(name) || t.aliases().contains(name))
            .findFirst();
    }

    /**
     * Database table: name, optional Javadoc-derived description, source
     * location, columns, and FK relations to other tables.
     */
    public record Table(
        String name,
        String description,
        SourceLocation definition,
        List<Column> columns,
        List<Reference> references
    ) {}

    /**
     * Column on a table.
     *
     * @param name        jOOQ Java field name (e.g. {@code "FILM_ID"}), not the SQL column name
     *                    (e.g. {@code "film_id"}). LSP completions suggest this form; diagnostics
     *                    accept SQL names via case-insensitive matching but emit a Warning.
     * @param description Javadoc for the column (e.g. lifted from
     *                    {@code COMMENT ON COLUMN}); empty if absent.
     * @param definition  source location of the column declaration in the
     *                    jOOQ-generated table class; {@link SourceLocation#UNKNOWN}
     *                    when the source is not on disk.
     */
    public record Column(
        String name,
        String graphqlType,
        boolean nullable,
        String description,
        SourceLocation definition
    ) {
        /** Test-friendly factory: location defaults to {@link SourceLocation#UNKNOWN}. */
        public static Column of(String name, String graphqlType, boolean nullable, String description) {
            return new Column(name, graphqlType, nullable, description, SourceLocation.UNKNOWN);
        }
    }

    /**
     * FK relation between tables.
     *
     * @param targetTable other table name
     * @param keyName     jOOQ Java field name of the FK ({@code <TABLE>__<FK>})
     * @param inverse     {@code true} if the other table holds the FK
     * @param definition  source location of the FK declaration in the
     *                    jOOQ-generated {@code Keys} class;
     *                    {@link SourceLocation#UNKNOWN} when the source is
     *                    not on disk.
     */
    public record Reference(
        String targetTable,
        String keyName,
        boolean inverse,
        SourceLocation definition
    ) {
        /** Test-friendly factory: location defaults to {@link SourceLocation#UNKNOWN}. */
        public static Reference of(String targetTable, String keyName, boolean inverse) {
            return new Reference(targetTable, keyName, inverse, SourceLocation.UNKNOWN);
        }
    }

    /**
     * GraphQL scalar type known to the generator.
     */
    public record TypeData(
        String name,
        List<String> aliases,
        String description,
        SourceLocation definition
    ) {}

    /**
     * Service / condition / record class plus its public methods.
     *
     * <p>{@code recordComponents} is populated when the class file's
     * {@code Record} attribute is present (i.e., a Java {@code record} class);
     * an empty list otherwise. The LSP's snapshot projection consumes this to
     * back {@code @field(name:)} completions / diagnostics / hovers under
     * {@code @record}-declared SDL types whose backing class is a Java
     * record.
     */
    public record ExternalReference(
        String name,
        String className,
        String description,
        List<Method> methods,
        List<RecordComponent> recordComponents
    ) {
        public ExternalReference {
            methods = List.copyOf(methods);
            recordComponents = List.copyOf(recordComponents);
        }

        public ExternalReference(String name, String className, String description, List<Method> methods) {
            this(name, className, description, methods, List.of());
        }
    }

    /**
     * One entry in a Java {@code record} class's component list — name plus
     * a rendered display type for hover. Source: the JVM
     * {@link java.lang.classfile.attribute.RecordAttribute} attribute on the
     * class file, read by {@link ClasspathScanner}.
     */
    public record RecordComponent(String name, String displayType) {}

    /**
     * Method on an {@link ExternalReference}.
     */
    public record Method(
        String name,
        String returnType,
        String description,
        List<Parameter> parameters
    ) {}

    /**
     * Method parameter. {@code source} matches the rewrite-side
     * {@code ParamSource} taxonomy (Arg, Context, Sources, DslContext,
     * Table, SourceTable). {@code name} is {@code null} when the class was
     * compiled without {@code -parameters}.
     */
    public record Parameter(String name, String type, String source, String description) {}

    /**
     * Source position: editor URI + line + column. Used for
     * goto-definition. Empty placeholder when the catalog can't compute a
     * real position.
     */
    public record SourceLocation(String uri, int line, int column) {

        public static final SourceLocation UNKNOWN = new SourceLocation("", 0, 0);
    }

    /**
     * Per-{@code @node}-type, author-supplied values. An entry exists in
     * {@link CompletionData#nodeMetadata()} for every GraphQL type whose
     * SDL carries {@code @node}, regardless of which axes the author
     * filled in. Presence in the map is the predicate the LSP's
     * {@code @nodeId(typeName:)} completion and validation arms read.
     *
     * <p>Pre-deduction: both axes are nullable to capture what the schema
     * author actually wrote. Cases where {@code typeId} or {@code keyColumns}
     * are deduced by the classifier (containing-type / unique-table / PK
     * inference) are invisible to in-editor feedback by design.
     *
     * @param typeId     value of {@code @node(typeId:)} if the author
     *                   declared it, else {@code null}
     * @param keyColumns values of {@code @node(keyColumns:)} if the author
     *                   declared the arg, else {@code null}; the column
     *                   names are author-supplied strings (jOOQ Java
     *                   constants or SQL column names; the classifier
     *                   resolves them)
     */
    public record NodeMetadata(String typeId, List<String> keyColumns) {}
}
