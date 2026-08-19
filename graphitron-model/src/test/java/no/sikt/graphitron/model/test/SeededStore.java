package no.sikt.graphitron.model.test;

import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERROR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_EXTERNAL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PIVOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SERVICE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.JVM_CLASS_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER_TYPE_REF;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_RETURN_TYPE_REF;
import static no.sikt.graphitron.model.Tables.JVM_RECORD_COMPONENT;
import static no.sikt.graphitron.model.Tables.JVM_RECORD_COMPONENT_TYPE_REF;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static no.sikt.graphitron.model.Tables.WALK_CLAIM_DOMAIN_FIELD;
import static no.sikt.graphitron.model.Tables.WALK_CLAIM_DOMAIN_TYPE;

/**
 * Rows, stated directly: one named helper per row family, over a store {@link FactStores} opened.
 * The harness for a test whose subject is what a relation this module declares returns given rows,
 * which is a view's joins, its outer edges, or a check constraint's boundary.
 *
 * <p>Seeding is the method here rather than an escape from one. This module declares the fact
 * schema and nothing else, so a case in it states its inputs as rows and asserts what the relation
 * makes of them, and reaching a state no crawler can produce is exactly what a view's edges want
 * exercised. That is the opposite obligation from the modules above: the generator, the language
 * server and the MCP server exist to turn real inputs into real rows, so a case up there fills its
 * store by running a real capture or a real build through that module's own harness, and one that
 * hand-seeds rows to skip the pipeline stops testing what the module does. The module boundary
 * carries the distinction, so neither side needs a naming convention to keep the two apart.
 *
 * <p>What a seeded case still owes its reader is a sentence, not a permission: a case standing in
 * for a state a real build reaches should say which one, and a case pinning a relation's own
 * algebra should say plainly that it is doing that. Javadoc habit, nothing this harness enforces.
 *
 * <p>Helpers are stateless and take the {@link DSLContext} first, so a case static-imports the ones
 * it uses and reads as a list of rows. Ordering follows the foreign keys: anchor the graph and the
 * sources, then the types, then what hangs off them. Helpers that several others depend on are
 * idempotent, so a case can seed two tables sharing a schema without tracking which call created
 * it; the rest insert once and let a duplicate fail loudly rather than passing silently.
 *
 * <p>Expect this set to grow. A case needing a shape no helper produces adds one here rather than
 * hand-rolling a private copy, which is the whole reason the helpers are in one file: two that turn
 * out to be the same are a mechanical afternoon to fold together, and a spread of private copies
 * that quietly disagree is not.
 */
public final class SeededStore {

    /**
     * The declaration site every seeded type carries. One spelling, so the composite foreign keys
     * from {@code graphql_field} and {@code graphitron_table} back to
     * {@code graphql_type_declaration} line up without a caller ever naming a line number.
     */
    private static final String SEED_SOURCE = "seed.graphqls";
    private static final int SEED_LINE = 1;
    private static final int SEED_COLUMN = 1;

    private SeededStore() {}

    // ===== Entry points =====

    /**
     * An empty in-memory store, for a case that anchors its own graphs or asserts on a relation
     * with no graph partition at all.
     */
    public static void withSeededStore(Consumer<DSLContext> body) {
        try (var store = FactStores.inMemory()) {
            body.accept(store.dsl());
        }
    }

    /**
     * An in-memory store with {@code graphName} already anchored, which is what a graph-keyed
     * relation needs before any row can reference it. A case wanting a second graph seeds it inside
     * the body with {@link #seedGraph}, which is how the sibling-partition negatives get something
     * to assert against.
     */
    public static void withSeededStore(String graphName, Consumer<DSLContext> body) {
        withSeededStore(dsl -> {
            seedGraph(dsl, graphName);
            body.accept(dsl);
        });
    }

    // ===== Anchors =====

    /** The graph row every graph-keyed family hangs off. */
    public static void seedGraph(DSLContext dsl, String graphName) {
        dsl.insertInto(STORE_GRAPH)
            .set(STORE_GRAPH.GRAPH_NAME, graphName)
            .set(STORE_GRAPH.BASE_DIR, "/seeded")
            .set(STORE_GRAPH.LAST_CAPTURED, LocalDateTime.now())
            .execute();
    }

    /**
     * A source row: a jOOQ package, a classpath entry or a schema file the store has read.
     * Idempotent, since several graphs routinely read one source.
     *
     * @param sourceKind {@code JOOQ_SCHEMA}, {@code DIRECTORY}, {@code JAR} or {@code SCHEMA_FILE};
     *        the DDL's own check constraint rejects anything else
     */
    public static void seedSource(DSLContext dsl, String sourceName, String sourceKind) {
        dsl.insertInto(STORE_SOURCE)
            .set(STORE_SOURCE.SOURCE_NAME, sourceName)
            .set(STORE_SOURCE.SOURCE_KIND, sourceKind)
            .set(STORE_SOURCE.LAST_SEEN, LocalDateTime.now())
            .onDuplicateKeyIgnore()
            .execute();
    }

    /**
     * Membership: this graph read this source. The row every source-keyed relation is scoped
     * through, so a seeded catalog or census the graph does not claim is invisible to it.
     * Idempotent, on {@link #seedSource}'s terms: a graph reading a source twice is one membership,
     * and a case stating two bindings over one catalog is asking for the source once.
     */
    public static void seedGraphSource(DSLContext dsl, String graphName, String sourceName) {
        dsl.insertInto(STORE_GRAPH_SOURCE)
            .set(STORE_GRAPH_SOURCE.GRAPH_NAME, graphName)
            .set(STORE_GRAPH_SOURCE.SOURCE_NAME, sourceName)
            .onDuplicateKeyIgnore()
            .execute();
    }

    // ===== The SDL families =====

    /**
     * A type's existence and its kind, with no declaration site. What a built-in scalar has: it is
     * named by a field's type and nothing declared it. A type whose fields or directive
     * applications need a site to hang off wants {@link #seedDeclaredType} instead.
     *
     * <p>Idempotent, so a helper that needs a type present can ask for it without knowing whether
     * the case already seeded it.
     */
    public static void seedType(DSLContext dsl, String graphName, String typeName, String kind) {
        if (dsl.fetchExists(GRAPHQL_TYPE, GRAPHQL_TYPE.GRAPH_NAME.eq(graphName)
                .and(GRAPHQL_TYPE.TYPE_NAME.eq(typeName)))) {
            return;
        }
        dsl.insertInto(GRAPHQL_TYPE)
            .set(GRAPHQL_TYPE.GRAPH_NAME, graphName)
            .set(GRAPHQL_TYPE.TYPE_NAME, typeName)
            .set(GRAPHQL_TYPE.KIND, kind)
            .execute();
    }

    /**
     * A type plus the one declaration site this harness spells, which is what a field or a
     * {@code @table} application resolves its own composite key against. Idempotent, on
     * {@link #seedType}'s terms.
     */
    public static void seedDeclaredType(DSLContext dsl, String graphName, String typeName, String kind) {
        seedType(dsl, graphName, typeName, kind);
        if (dsl.fetchExists(GRAPHQL_TYPE_DECLARATION,
                GRAPHQL_TYPE_DECLARATION.GRAPH_NAME.eq(graphName)
                    .and(GRAPHQL_TYPE_DECLARATION.TYPE_NAME.eq(typeName)))) {
            return;
        }
        dsl.insertInto(GRAPHQL_TYPE_DECLARATION)
            .set(GRAPHQL_TYPE_DECLARATION.GRAPH_NAME, graphName)
            .set(GRAPHQL_TYPE_DECLARATION.TYPE_NAME, typeName)
            .set(GRAPHQL_TYPE_DECLARATION.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHQL_TYPE_DECLARATION.SOURCE_LINE, SEED_LINE)
            .set(GRAPHQL_TYPE_DECLARATION.SOURCE_COLUMN, SEED_COLUMN)
            .set(GRAPHQL_TYPE_DECLARATION.MERGE_ORDINAL, 0)
            .set(GRAPHQL_TYPE_DECLARATION.IS_EXTENSION, false)
            .set(GRAPHQL_TYPE_DECLARATION.KIND, kind)
            .execute();
    }

    /**
     * One nullable scalar output field {@code typeName.fieldName: String}, on an object type this
     * seeds if the case has not. The {@code String} scalar comes with it, because a relation
     * reading a field's leaf kind joins {@code graphql_type} on the named type and finds nothing
     * otherwise.
     */
    public static void seedField(DSLContext dsl, String graphName, String typeName, String fieldName) {
        seedType(dsl, graphName, "String", "SCALAR");
        seedField(dsl, graphName, typeName, fieldName, "String", false);
    }

    /**
     * The same field with its type stated: which type it names, and whether it is a list of them.
     * The arm a case reaches for when the field's cardinality is what a relation compares against.
     *
     * <p>The named type is the case's to seed, only it knowing the kind; the four-argument form
     * above is the convenience that brings its own {@code String} because it also picks the type.
     */
    public static void seedField(DSLContext dsl, String graphName, String typeName, String fieldName,
                                 String namedType, boolean isList) {
        seedDeclaredType(dsl, graphName, typeName, "OBJECT");
        dsl.insertInto(GRAPHQL_FIELD)
            .set(GRAPHQL_FIELD.GRAPH_NAME, graphName)
            .set(GRAPHQL_FIELD.TYPE_NAME, typeName)
            .set(GRAPHQL_FIELD.FIELD_NAME, fieldName)
            .set(GRAPHQL_FIELD.ORDINAL, 0)
            .set(GRAPHQL_FIELD.DECLARATION_LINE, SEED_LINE)
            .set(GRAPHQL_FIELD.DECLARATION_COLUMN, SEED_COLUMN)
            .set(GRAPHQL_FIELD.TYPE_SDL, isList ? "[" + namedType + "]" : namedType)
            .set(GRAPHQL_FIELD.NAMED_TYPE, namedType)
            .set(GRAPHQL_FIELD.NON_NULL, false)
            .set(GRAPHQL_FIELD.IS_LIST, isList)
            .set(GRAPHQL_FIELD.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHQL_FIELD.SOURCE_LINE, 2)
            .set(GRAPHQL_FIELD.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * One nullable single-valued argument on a field that already exists. Named types are spelled
     * rather than resolved, as on {@link #seedField}, and the case seeds the type it names if
     * anything it asserts reads that type.
     *
     * <p>The arm a case reaches for when a relation's subject is whether arguments are read at all.
     */
    public static void seedArgument(DSLContext dsl, String graphName, String typeName,
                                    String fieldName, String argumentName, String namedType) {
        dsl.insertInto(GRAPHQL_ARGUMENT)
            .set(GRAPHQL_ARGUMENT.GRAPH_NAME, graphName)
            .set(GRAPHQL_ARGUMENT.TYPE_NAME, typeName)
            .set(GRAPHQL_ARGUMENT.FIELD_NAME, fieldName)
            .set(GRAPHQL_ARGUMENT.ARGUMENT_NAME, argumentName)
            .set(GRAPHQL_ARGUMENT.ORDINAL, 0)
            .set(GRAPHQL_ARGUMENT.TYPE_SDL, namedType)
            .set(GRAPHQL_ARGUMENT.NAMED_TYPE, namedType)
            .set(GRAPHQL_ARGUMENT.NON_NULL, false)
            .set(GRAPHQL_ARGUMENT.IS_LIST, false)
            .set(GRAPHQL_ARGUMENT.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHQL_ARGUMENT.SOURCE_LINE, 2)
            .set(GRAPHQL_ARGUMENT.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * What a macro left behind when it rewrote a field's type expression: the field's own row now
     * carries the effective type and this one carries the type the author wrote. A relation reading
     * the authored expression joins here, so a case about that reading states both spellings and the
     * two deliberately disagree.
     *
     * @param macro the expansion that rewrote it; {@code CONNECTION} is the only one the DDL accepts
     */
    public static void seedFieldSynthesis(DSLContext dsl, String graphName, String typeName,
                                          String fieldName, String macro, String authoredTypeSdl) {
        dsl.insertInto(GRAPHITRON_FIELD_SYNTHESIS)
            .set(GRAPHITRON_FIELD_SYNTHESIS.GRAPH_NAME, graphName)
            .set(GRAPHITRON_FIELD_SYNTHESIS.TYPE_NAME, typeName)
            .set(GRAPHITRON_FIELD_SYNTHESIS.FIELD_NAME, fieldName)
            .set(GRAPHITRON_FIELD_SYNTHESIS.MACRO, macro)
            .set(GRAPHITRON_FIELD_SYNTHESIS.AUTHORED_TYPE_SDL, authoredTypeSdl)
            .execute();
    }

    // ===== The directive applications =====

    /**
     * The namespace half of a written reference: everything left of its first period, null where it
     * carries none. A seeded reference arrives whole, as an author writes one, so the helpers below
     * fill its two parts the way a real capture would; a case wanting a partition the split would
     * not produce sets the columns itself.
     */
    private static String namespacePart(String written) {
        if (written == null) {
            return null;
        }
        int period = written.indexOf('.');
        return period < 0 ? null : written.substring(0, period);
    }

    /** The name half of a written reference: everything right of its first period, or all of it. */
    private static String namePart(String written) {
        if (written == null) {
            return null;
        }
        int period = written.indexOf('.');
        return period < 0 ? written : written.substring(period + 1);
    }

    /**
     * A {@code @table} application on a type: the reference as the author spelled it, unresolved.
     * Which catalog table it names is what the resolution relations answer, so a spelling that
     * matches nothing, or two things, is a state this helper is meant to reach.
     */
    public static void seedTableBinding(DSLContext dsl, String graphName, String typeName, String tableRef) {
        seedDeclaredType(dsl, graphName, typeName, "OBJECT");
        dsl.insertInto(GRAPHITRON_TABLE)
            .set(GRAPHITRON_TABLE.GRAPH_NAME, graphName)
            .set(GRAPHITRON_TABLE.TYPE_NAME, typeName)
            .set(GRAPHITRON_TABLE.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_TABLE.DECLARATION_LINE, SEED_LINE)
            .set(GRAPHITRON_TABLE.DECLARATION_COLUMN, SEED_COLUMN)
            .set(GRAPHITRON_TABLE.SOURCE_LINE, 1)
            .set(GRAPHITRON_TABLE.SOURCE_COLUMN, 20)
            .set(GRAPHITRON_TABLE.TABLE_REF, tableRef)
            .set(GRAPHITRON_TABLE.TABLE_REF_NAMESPACE_PART, namespacePart(tableRef))
            .set(GRAPHITRON_TABLE.TABLE_REF_NAME_PART, namePart(tableRef))
            .execute();
    }

    /**
     * A {@code @field} application on a field: the name the slot binds to, as the author wrote it.
     * What that name resolves against is the backing's business, so a spelling matching no column
     * and no member is an ordinary row here.
     */
    public static void seedFieldBinding(DSLContext dsl, String graphName, String typeName,
                                        String fieldName, String nameRef) {
        dsl.insertInto(GRAPHITRON_FIELD_BINDING)
            .set(GRAPHITRON_FIELD_BINDING.GRAPH_NAME, graphName)
            .set(GRAPHITRON_FIELD_BINDING.TYPE_NAME, typeName)
            .set(GRAPHITRON_FIELD_BINDING.FIELD_NAME, fieldName)
            .set(GRAPHITRON_FIELD_BINDING.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_FIELD_BINDING.SOURCE_LINE, 2)
            .set(GRAPHITRON_FIELD_BINDING.SOURCE_COLUMN, 3)
            .set(GRAPHITRON_FIELD_BINDING.NAME_REF, nameRef)
            .execute();
    }

    /** An {@code @error} application on a type: presence, which is the whole of what it states. */
    public static void seedError(DSLContext dsl, String graphName, String typeName) {
        seedDeclaredType(dsl, graphName, typeName, "OBJECT");
        dsl.insertInto(GRAPHITRON_ERROR)
            .set(GRAPHITRON_ERROR.GRAPH_NAME, graphName)
            .set(GRAPHITRON_ERROR.TYPE_NAME, typeName)
            .set(GRAPHITRON_ERROR.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_ERROR.DECLARATION_LINE, SEED_LINE)
            .set(GRAPHITRON_ERROR.DECLARATION_COLUMN, SEED_COLUMN)
            .set(GRAPHITRON_ERROR.SOURCE_LINE, 1)
            .set(GRAPHITRON_ERROR.SOURCE_COLUMN, 20)
            .execute();
    }

    /**
     * A {@code @pivot} application on a field: the two column names the projection turns on, as the
     * author wrote them and resolved against nothing.
     */
    public static void seedPivot(DSLContext dsl, String graphName, String typeName, String fieldName,
                                 String onColumn, String valueColumn) {
        dsl.insertInto(GRAPHITRON_PIVOT)
            .set(GRAPHITRON_PIVOT.GRAPH_NAME, graphName)
            .set(GRAPHITRON_PIVOT.TYPE_NAME, typeName)
            .set(GRAPHITRON_PIVOT.FIELD_NAME, fieldName)
            .set(GRAPHITRON_PIVOT.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_PIVOT.SOURCE_LINE, 2)
            .set(GRAPHITRON_PIVOT.SOURCE_COLUMN, 3)
            .set(GRAPHITRON_PIVOT.ON_COLUMN, onColumn)
            .set(GRAPHITRON_PIVOT.VALUE_COLUMN, valueColumn)
            .execute();
    }

    /**
     * A {@code @reference} application on a field: the path's own row, which its steps hang off.
     * The field has to exist already, the application being keyed by the coordinate.
     */
    public static void seedFieldReference(DSLContext dsl, String graphName, String typeName,
                                          String fieldName, int ordinal) {
        dsl.insertInto(GRAPHITRON_FIELD_REFERENCE)
            .set(GRAPHITRON_FIELD_REFERENCE.GRAPH_NAME, graphName)
            .set(GRAPHITRON_FIELD_REFERENCE.TYPE_NAME, typeName)
            .set(GRAPHITRON_FIELD_REFERENCE.FIELD_NAME, fieldName)
            .set(GRAPHITRON_FIELD_REFERENCE.ORDINAL, ordinal)
            .set(GRAPHITRON_FIELD_REFERENCE.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_FIELD_REFERENCE.SOURCE_LINE, 2)
            .set(GRAPHITRON_FIELD_REFERENCE.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * One element of a path, spelling a key, a table, or both, exactly as the author wrote them.
     * Either may be null; what each spelling resolves to is the relation under assertion.
     */
    public static void seedFieldReferenceStep(DSLContext dsl, String graphName, String typeName,
                                              String fieldName, int ordinal, int position,
                                              String tableRef, String keyRef) {
        dsl.insertInto(GRAPHITRON_FIELD_REFERENCE_STEP)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.GRAPH_NAME, graphName)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.TYPE_NAME, typeName)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.FIELD_NAME, fieldName)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.ORDINAL, ordinal)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.POSITION, position)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.TABLE_REF, tableRef)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.TABLE_REF_NAMESPACE_PART, namespacePart(tableRef))
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.TABLE_REF_NAME_PART, namePart(tableRef))
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.KEY_REF, keyRef)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.KEY_REF_NAMESPACE_PART, namespacePart(keyRef))
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.KEY_REF_NAME_PART, namePart(keyRef))
            .execute();
    }

    /**
     * One element of a path spelling a condition method instead of a hop: the class and the method
     * as the author wrote them, and neither a key nor a table. The arm exists so a case can state
     * that shape by name, an element naming neither being what several relations decline on.
     */
    public static void seedFieldReferenceCall(DSLContext dsl, String graphName, String typeName,
                                              String fieldName, int ordinal, int position,
                                              String className, String method) {
        dsl.insertInto(GRAPHITRON_FIELD_REFERENCE_STEP)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.GRAPH_NAME, graphName)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.TYPE_NAME, typeName)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.FIELD_NAME, fieldName)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.ORDINAL, ordinal)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.POSITION, position)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.CLASS_NAME, className)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.METHOD, method)
            .execute();
    }

    /**
     * A {@code @service} application on a field: the Java names as the author wrote them, neither
     * resolved against anything. Either may be null, a directive naming no method being a state the
     * resolution relations answer for rather than one a fixture is kept out of.
     */
    public static void seedService(DSLContext dsl, String graphName, String typeName, String fieldName,
                                   String className, String method) {
        dsl.insertInto(GRAPHITRON_SERVICE)
            .set(GRAPHITRON_SERVICE.GRAPH_NAME, graphName)
            .set(GRAPHITRON_SERVICE.TYPE_NAME, typeName)
            .set(GRAPHITRON_SERVICE.FIELD_NAME, fieldName)
            .set(GRAPHITRON_SERVICE.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_SERVICE.SOURCE_LINE, 2)
            .set(GRAPHITRON_SERVICE.SOURCE_COLUMN, 3)
            .set(GRAPHITRON_SERVICE.CLASS_NAME, className)
            .set(GRAPHITRON_SERVICE.METHOD, method)
            .execute();
    }

    /**
     * An {@code @externalField} application on a field, on {@link #seedService}'s terms. A null
     * method is the shape whose fallback to the field's own name a derivation supplies, so it is
     * stateable here rather than something a case has to reach through a real capture.
     */
    public static void seedExternalField(DSLContext dsl, String graphName, String typeName,
                                         String fieldName, String className, String method) {
        dsl.insertInto(GRAPHITRON_EXTERNAL_FIELD)
            .set(GRAPHITRON_EXTERNAL_FIELD.GRAPH_NAME, graphName)
            .set(GRAPHITRON_EXTERNAL_FIELD.TYPE_NAME, typeName)
            .set(GRAPHITRON_EXTERNAL_FIELD.FIELD_NAME, fieldName)
            .set(GRAPHITRON_EXTERNAL_FIELD.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_EXTERNAL_FIELD.SOURCE_LINE, 2)
            .set(GRAPHITRON_EXTERNAL_FIELD.SOURCE_COLUMN, 3)
            .set(GRAPHITRON_EXTERNAL_FIELD.CLASS_NAME, className)
            .set(GRAPHITRON_EXTERNAL_FIELD.METHOD, method)
            .execute();
    }

    // ===== The jOOQ catalog =====

    /** A schema of one source. Idempotent, since the tables under a schema seed one at a time. */
    public static void seedSchema(DSLContext dsl, String sourceName, String tableSchema) {
        dsl.insertInto(SQL_SCHEMA)
            .set(SQL_SCHEMA.SOURCE_NAME, sourceName)
            .set(SQL_SCHEMA.TABLE_SCHEMA, tableSchema)
            .set(SQL_SCHEMA.KEYS_CLASS_FQN, sourceName + ".Keys")
            .onDuplicateKeyIgnore()
            .execute();
    }

    /**
     * A catalog table, with the generated names jOOQ would have derived from it: the jOOQ name
     * upper-cased off the SQL name, and the table and record classes under the source's package.
     * A case that needs those to disagree with the SQL name states its own row instead.
     */
    public static void seedTable(DSLContext dsl, String sourceName, String tableSchema, String tableName) {
        seedTable(dsl, sourceName, tableSchema, tableName, "TABLE");
    }

    /**
     * The same row with the catalog's kind stated: {@code FUNCTION} for a table-valued function's
     * result, whose absence of a primary key and of foreign keys is what a reader turns on. Its own
     * overload rather than a fifth parameter on every call site, plain tables being what a case
     * seeds unless it is about the difference.
     */
    public static void seedTable(DSLContext dsl, String sourceName, String tableSchema,
                                 String tableName, String tableType) {
        seedSchema(dsl, sourceName, tableSchema);
        dsl.insertInto(SQL_TABLE)
            .set(SQL_TABLE.SOURCE_NAME, sourceName)
            .set(SQL_TABLE.TABLE_SCHEMA, tableSchema)
            .set(SQL_TABLE.TABLE_NAME, tableName)
            .set(SQL_TABLE.TABLE_TYPE, tableType)
            .set(SQL_TABLE.JOOQ_NAME, tableName.toUpperCase(Locale.ROOT))
            .set(SQL_TABLE.CLASS_FQN, sourceName + ".tables." + tableName)
            .set(SQL_TABLE.RECORD_CLASS_FQN, sourceName + ".tables.records." + tableName + "Record")
            .execute();
    }

    /**
     * A nullable text column. {@code jooqName} is stated rather than derived, the two-tier name
     * match being exactly what several relations decide on.
     */
    public static void seedColumn(DSLContext dsl, String sourceName, String tableSchema, String tableName,
                                  String columnName, int ordinal, String jooqName) {
        dsl.insertInto(SQL_COLUMN)
            .set(SQL_COLUMN.SOURCE_NAME, sourceName)
            .set(SQL_COLUMN.TABLE_SCHEMA, tableSchema)
            .set(SQL_COLUMN.TABLE_NAME, tableName)
            .set(SQL_COLUMN.COLUMN_NAME, columnName)
            .set(SQL_COLUMN.ORDINAL, ordinal)
            .set(SQL_COLUMN.JOOQ_NAME, jooqName)
            .set(SQL_COLUMN.SQL_TYPE, "character varying")
            .set(SQL_COLUMN.BINDING_TYPE, "java.lang.String")
            .set(SQL_COLUMN.NULLABLE, true)
            .execute();
    }

    /**
     * The node-identity metadata a generated table class stated, in full: both form arms and
     * whatever each arm carries. The general form, for a case whose subject is a form the crawler
     * only reaches from a malformed generated class; a case that just needs well-formed metadata
     * under it wants {@link #seedStatedNodeMetadata} instead.
     *
     * @param typeIdForm {@code STRING}, {@code NULL}, {@code OTHER} or {@code ABSENT}
     * @param typeId the stated string, non-null exactly on {@code STRING}
     * @param typeIdClass the stated value's class, non-null exactly on {@code OTHER}
     * @param keyColumnsForm {@code FIELD_ARRAY}, {@code NULL}, {@code OTHER} or {@code ABSENT}
     * @param keyColumnsClass the stated value's class, non-null exactly on {@code OTHER}
     */
    public static void seedNodeMetadata(DSLContext dsl, String sourceName, String tableSchema,
                                        String tableName, String typeIdForm, String typeId,
                                        String typeIdClass, String keyColumnsForm,
                                        String keyColumnsClass) {
        dsl.insertInto(SQL_NODE_METADATA)
            .set(SQL_NODE_METADATA.SOURCE_NAME, sourceName)
            .set(SQL_NODE_METADATA.TABLE_SCHEMA, tableSchema)
            .set(SQL_NODE_METADATA.TABLE_NAME, tableName)
            .set(SQL_NODE_METADATA.TYPE_ID_FORM, typeIdForm)
            .set(SQL_NODE_METADATA.TYPE_ID, typeId)
            .set(SQL_NODE_METADATA.TYPE_ID_CLASS, typeIdClass)
            .set(SQL_NODE_METADATA.KEY_COLUMNS_FORM, keyColumnsForm)
            .set(SQL_NODE_METADATA.KEY_COLUMNS_CLASS, keyColumnsClass)
            .execute();
    }

    /**
     * Metadata that stated a type-id string and an array beside it, which is what a class jOOQ
     * generated for a node-bearing table publishes. Whether the entries under it resolve is the
     * case's own business; this states only that both constants were declared in the expected form.
     */
    public static void seedStatedNodeMetadata(DSLContext dsl, String sourceName, String tableSchema,
                                              String tableName, String typeId) {
        seedNodeMetadata(dsl, sourceName, tableSchema, tableName,
            "STRING", typeId, null, "FIELD_ARRAY", null);
    }

    /**
     * One stated entry of the key-columns array. {@code columnName} is null exactly where the array
     * entry itself was null, and needs to resolve against no column: a name the table does not have
     * is the state the defect derivation exists to name.
     */
    public static void seedNodeKeyColumn(DSLContext dsl, String sourceName, String tableSchema,
                                         String tableName, int position, String columnName) {
        dsl.insertInto(SQL_NODE_KEY_COLUMN)
            .set(SQL_NODE_KEY_COLUMN.SOURCE_NAME, sourceName)
            .set(SQL_NODE_KEY_COLUMN.TABLE_SCHEMA, tableSchema)
            .set(SQL_NODE_KEY_COLUMN.TABLE_NAME, tableName)
            .set(SQL_NODE_KEY_COLUMN.POSITION, position)
            .set(SQL_NODE_KEY_COLUMN.COLUMN_NAME, columnName)
            .execute();
    }

    /**
     * A key on a catalog table.
     *
     * @param constraintType {@code PRIMARY KEY}, {@code UNIQUE} or {@code FOREIGN KEY}
     * @param jooqName the constant jOOQ generated for the key, or null where it generated none
     */
    public static void seedConstraint(DSLContext dsl, String sourceName, String tableSchema, String tableName,
                                      String constraintName, String constraintType, String jooqName) {
        dsl.insertInto(SQL_CONSTRAINT)
            .set(SQL_CONSTRAINT.SOURCE_NAME, sourceName)
            .set(SQL_CONSTRAINT.TABLE_SCHEMA, tableSchema)
            .set(SQL_CONSTRAINT.TABLE_NAME, tableName)
            .set(SQL_CONSTRAINT.CONSTRAINT_NAME, constraintName)
            .set(SQL_CONSTRAINT.CONSTRAINT_TYPE, constraintType)
            .set(SQL_CONSTRAINT.JOOQ_NAME, jooqName)
            .execute();
    }

    /**
     * Where a foreign key points. Both ends must already be constraints, so seed the referenced
     * key before the reference to it.
     */
    public static void seedReferentialConstraint(DSLContext dsl, String sourceName, String tableSchema,
                                                 String tableName, String constraintName,
                                                 String referencedSourceName, String referencedSchema,
                                                 String referencedTable, String referencedConstraintName) {
        dsl.insertInto(SQL_REFERENTIAL_CONSTRAINT)
            .set(SQL_REFERENTIAL_CONSTRAINT.SOURCE_NAME, sourceName)
            .set(SQL_REFERENTIAL_CONSTRAINT.TABLE_SCHEMA, tableSchema)
            .set(SQL_REFERENTIAL_CONSTRAINT.TABLE_NAME, tableName)
            .set(SQL_REFERENTIAL_CONSTRAINT.CONSTRAINT_NAME, constraintName)
            .set(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SOURCE_NAME, referencedSourceName)
            .set(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_SCHEMA, referencedSchema)
            .set(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_TABLE, referencedTable)
            .set(SQL_REFERENTIAL_CONSTRAINT.REFERENCED_CONSTRAINT_NAME, referencedConstraintName)
            .execute();
    }

    /**
     * A type bound to a catalog table and the table it names, in one call: the shape a case reaches
     * for when the binding is scenery rather than the subject. The source joins the graph's
     * membership, since a table outside it is not one this graph can resolve against.
     */
    public static void seedBoundTable(DSLContext dsl, String graphName, String typeName, String tableRef,
                                      String sourceName, String tableSchema, String tableName) {
        seedTableBinding(dsl, graphName, typeName, tableRef);
        seedSource(dsl, sourceName, "JOOQ_SCHEMA");
        seedGraphSource(dsl, graphName, sourceName);
        seedTable(dsl, sourceName, tableSchema, tableName);
    }

    // ===== The classpath census =====

    /**
     * A class the census reached, under the classpath entry that declared it.
     *
     * @param classKind {@code CLASS}, {@code INTERFACE}, {@code ENUM}, {@code RECORD} or
     *        {@code ANNOTATION}
     */
    public static void seedClass(DSLContext dsl, String sourceName, String className, String classKind) {
        dsl.insertInto(JVM_CLASS)
            .set(JVM_CLASS.SOURCE_NAME, sourceName)
            .set(JVM_CLASS.CLASS_NAME, className)
            .set(JVM_CLASS.CLASS_KIND, classKind)
            .execute();
    }

    /**
     * One declared supertype edge. The subtype has to be a census class already; the supertype
     * deliberately does not, a chain ending at a name no entry declares being the ordinary case
     * rather than a broken fixture.
     *
     * @param declaredVia {@code EXTENDS} or {@code IMPLEMENTS}
     */
    public static void seedSupertype(DSLContext dsl, String sourceName, String className,
                                     String supertypeName, String declaredVia) {
        dsl.insertInto(JVM_CLASS_SUPERTYPE)
            .set(JVM_CLASS_SUPERTYPE.SOURCE_NAME, sourceName)
            .set(JVM_CLASS_SUPERTYPE.CLASS_NAME, className)
            .set(JVM_CLASS_SUPERTYPE.SUPERTYPE_NAME, supertypeName)
            .set(JVM_CLASS_SUPERTYPE.DECLARED_VIA, declaredVia)
            .execute();
    }

    /**
     * A public method on a census class, which must already be one. The descriptor is the whole of
     * what tells two overloads of a name apart, so a case stating two rows of one name states two
     * descriptors and nothing else.
     */
    public static void seedMethod(DSLContext dsl, String sourceName, String className,
                                  String methodName, String descriptor) {
        seedMethod(dsl, sourceName, className, methodName, descriptor, Map.of());
    }

    /**
     * The same method with the classes its declared return type names, keyed by the position each
     * sits at: the empty path is the type itself and a digit is a 0-based type argument, so
     * {@code Map<String, List<Film>>} is four entries at {@code ""}, {@code "0"}, {@code "1"} and
     * {@code "1.0"}. A position naming no class simply has no entry, which is how a primitive or an
     * array return is stated: with an empty map.
     *
     * <p>Every position is invariant. A case about variance states its own rows, that being a shape
     * no reader has wanted from here yet rather than one this helper refuses.
     */
    public static void seedMethod(DSLContext dsl, String sourceName, String className,
                                  String methodName, String descriptor,
                                  Map<String, String> declaredReturn) {
        dsl.insertInto(JVM_METHOD)
            .set(JVM_METHOD.SOURCE_NAME, sourceName)
            .set(JVM_METHOD.CLASS_NAME, className)
            .set(JVM_METHOD.METHOD_NAME, methodName)
            .set(JVM_METHOD.DESCRIPTOR, descriptor)
            .set(JVM_METHOD.RETURN_TYPE, "Object")
            .set(JVM_METHOD.DECLARED_RETURN_TYPE, "Object")
            .set(JVM_METHOD.RETURNS_CONDITION, false)
            .execute();
        declaredReturn.forEach((typePath, referencedClass) ->
            dsl.insertInto(JVM_METHOD_RETURN_TYPE_REF)
                .set(JVM_METHOD_RETURN_TYPE_REF.SOURCE_NAME, sourceName)
                .set(JVM_METHOD_RETURN_TYPE_REF.CLASS_NAME, className)
                .set(JVM_METHOD_RETURN_TYPE_REF.METHOD_NAME, methodName)
                .set(JVM_METHOD_RETURN_TYPE_REF.DESCRIPTOR, descriptor)
                .set(JVM_METHOD_RETURN_TYPE_REF.TYPE_PATH, typePath)
                .set(JVM_METHOD_RETURN_TYPE_REF.REFERENCED_CLASS, referencedClass)
                .set(JVM_METHOD_RETURN_TYPE_REF.VARIANCE, "NONE")
                .execute());
    }

    /**
     * One position of a declared return type at a variance the map form cannot state, the method
     * and its remaining positions having been seeded by {@link #seedMethod}. A case about variance
     * states this row for the position it is about and leaves the rest invariant.
     *
     * @param variance {@code NONE}, {@code EXTENDS} or {@code SUPER}
     */
    public static void seedReturnTypeRef(DSLContext dsl, String sourceName, String className,
                                         String methodName, String descriptor, String typePath,
                                         String referencedClass, String variance) {
        dsl.insertInto(JVM_METHOD_RETURN_TYPE_REF)
            .set(JVM_METHOD_RETURN_TYPE_REF.SOURCE_NAME, sourceName)
            .set(JVM_METHOD_RETURN_TYPE_REF.CLASS_NAME, className)
            .set(JVM_METHOD_RETURN_TYPE_REF.METHOD_NAME, methodName)
            .set(JVM_METHOD_RETURN_TYPE_REF.DESCRIPTOR, descriptor)
            .set(JVM_METHOD_RETURN_TYPE_REF.TYPE_PATH, typePath)
            .set(JVM_METHOD_RETURN_TYPE_REF.REFERENCED_CLASS, referencedClass)
            .set(JVM_METHOD_RETURN_TYPE_REF.VARIANCE, variance)
            .execute();
    }

    /**
     * One parameter of a census method, which must already be one, with the classes its declared
     * type names on {@link #seedMethod}'s terms for the position map. A parameter naming no class
     * anywhere, a primitive one, is stated with an empty map.
     *
     * <p>Two rules read these rows and read them differently: the peel decomposes the declared type
     * under the parameter's own ordinal, and the member-slot rule reads the mere presence of a
     * parameter row as the method being no slot. A case wanting only the second states an empty map.
     *
     * <p>The parameter's own name is deliberately not an argument here. It is NULL for a consumer
     * compiled without {@code -parameters}, so the ordinal is the identity and nothing reading these
     * rows may depend on a name being there.
     */
    public static void seedMethodParameter(DSLContext dsl, String sourceName, String className,
                                           String methodName, String descriptor, int position,
                                           Map<String, String> declaredType) {
        dsl.insertInto(JVM_METHOD_PARAMETER)
            .set(JVM_METHOD_PARAMETER.SOURCE_NAME, sourceName)
            .set(JVM_METHOD_PARAMETER.CLASS_NAME, className)
            .set(JVM_METHOD_PARAMETER.METHOD_NAME, methodName)
            .set(JVM_METHOD_PARAMETER.DESCRIPTOR, descriptor)
            .set(JVM_METHOD_PARAMETER.POSITION, position)
            .set(JVM_METHOD_PARAMETER.PARAMETER_TYPE, "Object")
            .set(JVM_METHOD_PARAMETER.DECLARED_PARAMETER_TYPE, "Object")
            .execute();
        declaredType.forEach((typePath, referencedClass) ->
            dsl.insertInto(JVM_METHOD_PARAMETER_TYPE_REF)
                .set(JVM_METHOD_PARAMETER_TYPE_REF.SOURCE_NAME, sourceName)
                .set(JVM_METHOD_PARAMETER_TYPE_REF.CLASS_NAME, className)
                .set(JVM_METHOD_PARAMETER_TYPE_REF.METHOD_NAME, methodName)
                .set(JVM_METHOD_PARAMETER_TYPE_REF.DESCRIPTOR, descriptor)
                .set(JVM_METHOD_PARAMETER_TYPE_REF.POSITION, position)
                .set(JVM_METHOD_PARAMETER_TYPE_REF.TYPE_PATH, typePath)
                .set(JVM_METHOD_PARAMETER_TYPE_REF.REFERENCED_CLASS, referencedClass)
                .set(JVM_METHOD_PARAMETER_TYPE_REF.VARIANCE, "NONE")
                .execute());
    }

    /**
     * A record component on a census class, which must already be one and must have been declared a
     * {@code RECORD} for anything above the census to read the component. The classes its declared
     * type names are stated on {@link #seedMethod}'s terms.
     *
     * <p>The component's position in the record header is the order components are seeded in. No
     * relation above the census reads it: declaration order is deliberately not carried up, so a
     * case that asserted on it would be asserting on this helper.
     */
    public static void seedRecordComponent(DSLContext dsl, String sourceName, String className,
                                           String componentName, Map<String, String> declaredType) {
        dsl.insertInto(JVM_RECORD_COMPONENT)
            .set(JVM_RECORD_COMPONENT.SOURCE_NAME, sourceName)
            .set(JVM_RECORD_COMPONENT.CLASS_NAME, className)
            .set(JVM_RECORD_COMPONENT.COMPONENT_NAME, componentName)
            .set(JVM_RECORD_COMPONENT.POSITION, dsl.fetchCount(JVM_RECORD_COMPONENT,
                JVM_RECORD_COMPONENT.SOURCE_NAME.eq(sourceName)
                    .and(JVM_RECORD_COMPONENT.CLASS_NAME.eq(className))))
            .set(JVM_RECORD_COMPONENT.DISPLAY_TYPE, "Object")
            .set(JVM_RECORD_COMPONENT.DECLARED_TYPE, "Object")
            .execute();
        declaredType.forEach((typePath, referencedClass) ->
            dsl.insertInto(JVM_RECORD_COMPONENT_TYPE_REF)
                .set(JVM_RECORD_COMPONENT_TYPE_REF.SOURCE_NAME, sourceName)
                .set(JVM_RECORD_COMPONENT_TYPE_REF.CLASS_NAME, className)
                .set(JVM_RECORD_COMPONENT_TYPE_REF.COMPONENT_NAME, componentName)
                .set(JVM_RECORD_COMPONENT_TYPE_REF.TYPE_PATH, typePath)
                .set(JVM_RECORD_COMPONENT_TYPE_REF.REFERENCED_CLASS, referencedClass)
                .set(JVM_RECORD_COMPONENT_TYPE_REF.VARIANCE, "NONE")
                .execute());
    }

    // ===== The walk's own registries =====

    /**
     * One coordinate the classification walk registered. A relation that gates on the walk having
     * reached a field joins this, so a case about such a gate states the membership beside the facts
     * rather than being unable to reach the gated arm at all.
     */
    public static void seedClaimDomainField(DSLContext dsl, String graphName, String typeName,
                                            String fieldName) {
        dsl.insertInto(WALK_CLAIM_DOMAIN_FIELD)
            .set(WALK_CLAIM_DOMAIN_FIELD.GRAPH_NAME, graphName)
            .set(WALK_CLAIM_DOMAIN_FIELD.TYPE_NAME, typeName)
            .set(WALK_CLAIM_DOMAIN_FIELD.FIELD_NAME, fieldName)
            .execute();
    }

    /**
     * The same registration at the type grain. Its own membership set rather than one derived from
     * the field rows, exactly as the two tables are, so a case states whichever grain it is about.
     */
    public static void seedClaimDomainType(DSLContext dsl, String graphName, String typeName) {
        dsl.insertInto(WALK_CLAIM_DOMAIN_TYPE)
            .set(WALK_CLAIM_DOMAIN_TYPE.GRAPH_NAME, graphName)
            .set(WALK_CLAIM_DOMAIN_TYPE.TYPE_NAME, typeName)
            .execute();
    }
}
