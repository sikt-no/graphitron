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
     * Database table: name, optional Javadoc-derived description, the
     * fully-qualified name of the jOOQ-generated table class, columns, and FK
     * relations to other tables.
     *
     * @param classFqn fully-qualified name of the generated jOOQ table class
     *                 (e.g. {@code <jooqPackage>.tables.Film}), or {@code null}
     *                 when the table is not resolvable in the catalog. The LSP
     *                 goto-definition / hover paths join this FQN against the
     *                 LSP-owned {@link SourceWalker.Index} at request time, so the
     *                 table / column position rides the {@code .java} source
     *                 cadence rather than the generator build cadence (R352,
     *                 mirroring the service half R349 introduced). The catalog
     *                 itself holds no source position.
     */
    public record Table(
        String name,
        String description,
        String classFqn,
        List<Column> columns,
        List<Reference> references
    ) {}

    /**
     * Column on a table. Holds no source position: goto-definition joins the
     * {@code (owning-table classFqn, name)} key against the LSP-owned
 * {@link SourceWalker.Index} at request time.
     *
     * @param name        jOOQ Java field name (e.g. {@code "FILM_ID"}), not the SQL column name
     *                    (e.g. {@code "film_id"}). LSP completions suggest this form; diagnostics
     *                    accept SQL names via case-insensitive matching but emit a Warning.
     * @param description Javadoc for the column (e.g. lifted from
     *                    {@code COMMENT ON COLUMN}); empty if absent.
     */
    public record Column(
        String name,
        String graphqlType,
        boolean nullable,
        String description
    ) {
        /** Test-friendly factory alias for the canonical constructor. */
        public static Column of(String name, String graphqlType, boolean nullable, String description) {
            return new Column(name, graphqlType, nullable, description);
        }
    }

    /**
     * FK relation between tables. Holds no source position: goto-definition
     * joins the {@code (keysClassFqn, keyName)} field key against the LSP-owned
 * {@link SourceWalker.Index} at request time.
     *
     * @param targetTable  other table name
     * @param keyName      jOOQ Java field name of the FK ({@code <TABLE>__<FK>}),
     *                     a static field on the generated {@code Keys} class
     * @param inverse      {@code true} if the other table holds the FK
     * @param keysClassFqn fully-qualified name of the generated jOOQ
     *                     {@code Keys} class (e.g. {@code <jooqPackage>.Keys}),
     *                     or {@code null} when not resolvable
     */
    public record Reference(
        String targetTable,
        String keyName,
        boolean inverse,
        String keysClassFqn
    ) {
        /** Test-friendly factory: no {@code Keys} class FQN (no goto-definition target). */
        public static Reference of(String targetTable, String keyName, boolean inverse) {
            return new Reference(targetTable, keyName, inverse, null);
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
     * reflection-bound SDL types whose backing class is a Java
     * record.
     *
     * <p>{@code scalarConstants} lists this class's {@code public static}
 * {@code GraphQLScalarType} fields; it backs {@code @scalarType(scalar:)}
     * completion, which composes {@code className + "." + fieldName} for each.
     */
    public record ExternalReference(
        String name,
        String className,
        String description,
        List<Method> methods,
        List<RecordComponent> recordComponents,
        List<ScalarConstant> scalarConstants
    ) {
        public ExternalReference {
            methods = List.copyOf(methods);
            recordComponents = List.copyOf(recordComponents);
            scalarConstants = List.copyOf(scalarConstants);
        }

        /**
         * Back-compat constructor defaulting {@code scalarConstants} to an empty
         * list. Keeps existing LSP / test callers that build
         * {@link ExternalReference} without the R464 scalar-constant slot
         * compiling unchanged.
         */
        public ExternalReference(
            String name,
            String className,
            String description,
            List<Method> methods,
            List<RecordComponent> recordComponents
        ) {
            this(name, className, description, methods, recordComponents, List.of());
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
     * One {@code public static GraphQLScalarType} field on an
     * {@link ExternalReference} — the field name only; the owning class FQN is
     * {@link ExternalReference#className()}, so {@code @scalarType(scalar:)}
     * completion composes {@code className + "." + fieldName} (matching the
     * {@link RecordComponent} / {@link Method} shape). Source: the JVM field
     * table read by {@link ClasspathScanner}, matching on the exact
 * {@code GraphQLScalarType} field descriptor.
     */
    public record ScalarConstant(String fieldName) {}

    /**
     * Method on an {@link ExternalReference}. Carries the bytecode-derived
     * structure (name, return type, parameters); it holds no source position.
     * goto-definition for a method resolves its position at request time by
     * joining this method's {@code (className, name, paramCount)} key against
     * the LSP-owned {@link SourceWalker.Index}, so a position that becomes
     * available on a {@code .java} edit is seen without a generator rebuild.
     * An overload collision the join key cannot disambiguate is a distinct
     * outcome there, not a silent no-jump (see the LSP {@code DefinitionTarget}).
     *
     * <p>{@code returnsCondition} is the parse-boundary classification of
     * whether this method's return type is jOOQ's {@code org.jooq.Condition}
 *. {@link ClasspathScanner} computes it from the <em>un-erased</em>
     * return descriptor before {@code returnType} loses its package, so the
     * fact is exact (a consumer's own type named {@code Condition} does not
     * match). The MCP {@code conditions} tool and any future LSP
     * {@code @condition} arm read this pre-classified value rather than
     * re-deriving a fragile simple-name predicate from {@code returnType}.
     */
    public record Method(
        String name,
        String returnType,
        String description,
        List<Parameter> parameters,
        boolean returnsCondition
    ) {
        /**
         * Back-compat constructor defaulting {@code returnsCondition} to
         * {@code false} (a non-condition method). Keeps existing LSP / test
         * callers that build {@link Method} without the R368 classification
         * compiling unchanged.
         */
        public Method(String name, String returnType, String description, List<Parameter> parameters) {
            this(name, returnType, description, parameters, false);
        }
    }

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
