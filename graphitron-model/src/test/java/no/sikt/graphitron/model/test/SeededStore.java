package no.sikt.graphitron.model.test;

import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.JVM_CLASS_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

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
     */
    public static void seedGraphSource(DSLContext dsl, String graphName, String sourceName) {
        dsl.insertInto(STORE_GRAPH_SOURCE)
            .set(STORE_GRAPH_SOURCE.GRAPH_NAME, graphName)
            .set(STORE_GRAPH_SOURCE.SOURCE_NAME, sourceName)
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
        seedDeclaredType(dsl, graphName, typeName, "OBJECT");
        seedType(dsl, graphName, "String", "SCALAR");
        dsl.insertInto(GRAPHQL_FIELD)
            .set(GRAPHQL_FIELD.GRAPH_NAME, graphName)
            .set(GRAPHQL_FIELD.TYPE_NAME, typeName)
            .set(GRAPHQL_FIELD.FIELD_NAME, fieldName)
            .set(GRAPHQL_FIELD.ORDINAL, 0)
            .set(GRAPHQL_FIELD.DECLARATION_LINE, SEED_LINE)
            .set(GRAPHQL_FIELD.DECLARATION_COLUMN, SEED_COLUMN)
            .set(GRAPHQL_FIELD.TYPE_SDL, "String")
            .set(GRAPHQL_FIELD.NAMED_TYPE, "String")
            .set(GRAPHQL_FIELD.NON_NULL, false)
            .set(GRAPHQL_FIELD.IS_LIST, false)
            .set(GRAPHQL_FIELD.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHQL_FIELD.SOURCE_LINE, 2)
            .set(GRAPHQL_FIELD.SOURCE_COLUMN, 3)
            .execute();
    }

    // ===== The directive applications =====

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
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.KEY_REF, keyRef)
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
        seedSchema(dsl, sourceName, tableSchema);
        dsl.insertInto(SQL_TABLE)
            .set(SQL_TABLE.SOURCE_NAME, sourceName)
            .set(SQL_TABLE.TABLE_SCHEMA, tableSchema)
            .set(SQL_TABLE.TABLE_NAME, tableName)
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
}
