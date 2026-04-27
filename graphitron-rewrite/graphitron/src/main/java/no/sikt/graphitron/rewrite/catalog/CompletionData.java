package no.sikt.graphitron.rewrite.catalog;

import java.util.List;
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
    List<ExternalReference> externalReferences
) {

    public static CompletionData empty() {
        return new CompletionData(List.of(), List.of(), List.of());
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
     * Service / condition / record class plus its public methods. Spike
     * shape is minimal; method signatures and Javadoc land with Phase 5.
     */
    public record ExternalReference(
        String name,
        String className,
        String description,
        List<Method> methods
    ) {}

    /**
     * Method on an {@link ExternalReference}. Parameters and return type are
     * placeholder fields in Phase 0; the {@code MethodRef.ParamSource} port
     * (Phase 5) fills them in.
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
}
