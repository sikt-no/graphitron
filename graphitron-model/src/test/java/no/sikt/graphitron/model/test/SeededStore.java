package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.derive.ArgMappingCandidates;
import no.sikt.graphitron.model.derive.Materializations;
import no.sikt.graphitron.model.grammar.QualifiedNameGrammar;
import org.jooq.DSLContext;
import org.jooq.Record2;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARG_MAPPING_PAIR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_CONDITION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_LOOKUP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_PATH_SEGMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ARGUMENT_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_CONNECTION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ERROR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FACET;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_EXTERNAL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_BINDING;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_CONDITION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_LOOKUP_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_NODE_ID;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_REFERENCE_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_KEY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_KEY_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FEDERATION_KEY_FIELD_SEGMENT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_FIELD_SYNTHESIS;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_LINK;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_MUTATION;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ORDER_BY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_PIVOT;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_REFERENCE_FOR;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_REFERENCE_FOR_STEP;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_ROUTINE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SERVICE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_SPLIT_QUERY;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TABLE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_TENANT_FAN_OUT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ARGUMENT_COORDINATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_COORDINATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_FIELD_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_ROOT_OPERATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_COORDINATE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DECLARATION;
import static no.sikt.graphitron.model.Tables.GRAPHQL_TYPE_DIRECTIVE;
import static no.sikt.graphitron.model.Tables.GRAPHQL_POLY_MEMBER;
import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_PATH;
import static no.sikt.graphitron.model.Tables.INTENT_INPUT_OCCURRENCE_PATH_STEP;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CLASS;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_DOMAIN;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.JVM_CLASS_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.JVM_RECORD_COMPONENT;
import static no.sikt.graphitron.model.Tables.JVM_DECLARED_TYPE_REF;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ENUM_BINDING;
import static no.sikt.graphitron.model.Tables.SQL_NODE_KEY_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_NODE_METADATA;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE;
import static no.sikt.graphitron.model.Tables.SQL_ROUTINE_PARAMETER;
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
     *
     * <p>Empty rather than fresh, and the difference is only ever visible as speed. The store
     * belongs to the calling thread, which booted it on its first case and keeps it;
     * {@link ThreadConfinedStore} clears every relation a case can write to before handing it over,
     * so a body meets what a boot would have given it without paying for one. Nesting two calls on
     * one thread throws rather than silently sharing, since the inner clear would empty the outer
     * body's rows.
     */
    public static void withSeededStore(Consumer<DSLContext> body) {
        ThreadConfinedStore.run(body);
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

    // ===== The derivation boundary =====

    /**
     * Runs the derivations, which a case calls once its rows are seeded and before it reads a
     * derived relation. Every read helper in this fixture's tests starts with this line.
     *
     * <p>The store's architecture has three strata: capture transcribes facts, derivation computes
     * further facts from them, and queries read. This class models the first and the third, and
     * under derive-on-read the middle one is implicit and free, so its absence never cost anything.
     * Materialize one derivation and the missing stratum becomes visible at once: a target holds
     * rows only once something fills it. This is that stratum, and it is deliberately an entry
     * point production also calls, so the two boundaries cannot drift apart and neither holds a
     * list of relations a later registration could invalidate. A test never names a materialized
     * relation, and registering a fourth or a fifth costs this fixture nothing.
     *
     * <p>Every graph the store holds rather than one the caller names, which is the shape a seeded
     * store wants: a case seeds its graphs and then reads, several of them read across graphs to
     * assert partition isolation, and picking per helper would be a judgement each one could get
     * wrong for no gain. Idempotent, and on a store of a dozen rows the cost does not register, so
     * a helper calls it unconditionally rather than reasoning about whether an earlier one already
     * did.
     */
    public static void derive(DSLContext dsl) {
        transcribeSupertypes(dsl);
        // The candidate tree, by the same call capture makes. Stated here rather than seeded row by
        // row because the rule is pure SQL over rows a case has already seeded, so running it is
        // both cheaper than a per-case seeder and one fewer place for a fixture to hold its own
        // idea of the descent.
        for (var graph : dsl.select(STORE_GRAPH.GRAPH_NAME).from(STORE_GRAPH)
                .fetch(STORE_GRAPH.GRAPH_NAME)) {
            ArgMappingCandidates.derive(dsl, graph);
        }
        Materializations.refreshAll(dsl);
    }

    /**
     * Fills the three relations capture writes beside a site rather than instead of it, from the site
     * rows a case seeded. Stands in for capture's second write, and lives here rather than in the
     * seed helpers for a reason about when it can run: capture knows a spelling is authored at the
     * moment it reads the directive, and a fixture only knows which sites exist once the case has
     * finished seeding, so the transcription has to be late. {@link #derive} is the line every case
     * already calls at exactly that moment.
     *
     * <p>Beside rather than instead of is the whole of what selects a relation into this method,
     * and the argMapping pair relation is the counterexample worth naming because it used to be
     * here. Its sites carried nothing of their own, so the schema collapsed them into it and there
     * is no second write left to stand in for: a case seeds that relation directly, through
     * {@link #seedServiceArgMappingPair} and its siblings, which is both cheaper and one fewer
     * place for a fixture to disagree with itself.
     *
     * <p>The method reference is the mixed case and shows both halves at once. Nine of its sites
     * keep relations of their own and are transcribed from them below; the tenth, the source row,
     * carried nothing beyond the shared fact, so it has no relation to transcribe from and a case
     * meaning that site writes {@code graphitron_method_reference} itself.
     *
     * <p>Modelling capture rather than deriving anything, which is why it is named apart from the
     * refresh below it and why the refresh keeps the property of being an entry point production
     * also calls. A supertype is a captured fact: production writes it in the same walk that writes
     * the site, and nothing in the schema derives it, so a store whose sites were seeded by hand has
     * to be given it by hand too.
     *
     * <p>Idempotent by rewriting rather than by merging, because {@link #derive} is documented as
     * safe to call unconditionally and a case that seeds a second graph after a first read would
     * otherwise leave the first graph's rows behind a stale copy of themselves.
     */
    public static void transcribeSupertypes(DSLContext dsl) {
        dsl.execute("DELETE FROM graphitron_spelled_reference");
        dsl.execute("""
            INSERT INTO graphitron_spelled_reference (graph_name, spelling, namespace_part, name_part)
            SELECT graph_name, spelling, namespace_part, name_part FROM (
              SELECT graph_name, COALESCE(table_ref, type_name) AS spelling,
                     table_ref_namespace_part AS namespace_part,
                     COALESCE(table_ref_name_part, type_name) AS name_part
                FROM graphitron_table
              UNION
              SELECT graph_name, table_ref, table_ref_namespace_part, table_ref_name_part
                FROM graphitron_field_reference_step WHERE table_ref IS NOT NULL
              UNION
              SELECT graph_name, table_ref, table_ref_namespace_part, table_ref_name_part
                FROM graphitron_argument_reference_step WHERE table_ref IS NOT NULL
              UNION
              SELECT graph_name, table_ref, table_ref_namespace_part, table_ref_name_part
                FROM graphitron_reference_for_step WHERE table_ref IS NOT NULL
              UNION
              SELECT graph_name, table_ref, table_ref_namespace_part, table_ref_name_part
                FROM graphitron_argument_reference_for_step WHERE table_ref IS NOT NULL
              UNION
              SELECT graph_name, table_ref, table_ref_namespace_part, table_ref_name_part
                FROM graphitron_mutation WHERE table_ref IS NOT NULL
              UNION
              SELECT graph_name, routine_ref, routine_ref_namespace_part, routine_ref_name_part
                FROM graphitron_routine) spellings
            """);
        dsl.execute("DELETE FROM graphitron_field_navigation");
        dsl.execute("""
            INSERT INTO graphitron_field_navigation
              (graph_name, type_name, field_name, basis, navigated_type_name)
            SELECT f.graph_name, f.type_name, f.field_name,
                   CASE WHEN fs.field_name IS NOT NULL THEN 'AUTHORED_EXPRESSION'
                        WHEN ce.type_name IS NOT NULL THEN 'CONNECTION_ELEMENT'
                        ELSE 'NAMED_TYPE' END,
                   COALESCE(
                     REPLACE(REPLACE(REPLACE(fs.authored_type_sdl, '[', ''), ']', ''), '!', ''),
                     ce.element_type_name,
                     f.named_type)
              FROM graphql_field f
              LEFT JOIN graphitron_field_synthesis fs
                ON fs.graph_name = f.graph_name AND fs.type_name = f.type_name
               AND fs.field_name = f.field_name
              LEFT JOIN intent_connection_element_type ce
                ON ce.graph_name = f.graph_name AND ce.type_name = f.named_type
            """);
        dsl.execute("DELETE FROM graphitron_method_reference");
        dsl.execute("""
            INSERT INTO graphitron_method_reference
              (graph_name, site, use_site, type_name, field_name, argument_name, ordinal,
               step_position, class_name, method, source_name, source_line, source_column)
            SELECT graph_name, 'ENUM', type_name, type_name, NULL, NULL, NULL, NULL,
                   class_name, method, source_name, source_line, source_column
              FROM graphitron_enum
             WHERE class_name IS NOT NULL AND method IS NOT NULL
            UNION ALL
            SELECT graph_name, 'SERVICE', type_name || '.' || field_name,
                   type_name, field_name, NULL, NULL, NULL,
                   class_name, method, source_name, source_line, source_column
              FROM graphitron_service
             WHERE class_name IS NOT NULL AND method IS NOT NULL
            UNION ALL
            SELECT graph_name, 'EXTERNAL_FIELD', type_name || '.' || field_name,
                   type_name, field_name, NULL, NULL, NULL,
                   class_name, method, source_name, source_line, source_column
              FROM graphitron_external_field
             WHERE class_name IS NOT NULL AND method IS NOT NULL
            UNION ALL
            SELECT c.graph_name,
                   CASE WHEN t.kind = 'INPUT_OBJECT' THEN 'INPUT_FIELD_CONDITION'
                        ELSE 'FIELD_CONDITION' END,
                   c.type_name || '.' || c.field_name,
                   c.type_name, c.field_name, NULL, NULL, NULL,
                   c.class_name, c.method, c.source_name, c.source_line, c.source_column
              FROM graphitron_field_condition c
              JOIN graphql_type t ON t.graph_name = c.graph_name AND t.type_name = c.type_name
             WHERE c.class_name IS NOT NULL AND c.method IS NOT NULL
            UNION ALL
            SELECT graph_name, 'ARGUMENT_CONDITION',
                   type_name || '.' || field_name || '(' || argument_name || ')',
                   type_name, field_name, argument_name, NULL, NULL,
                   class_name, method, source_name, source_line, source_column
              FROM graphitron_argument_condition
             WHERE class_name IS NOT NULL AND method IS NOT NULL
            UNION ALL
            SELECT s.graph_name, 'FIELD_REFERENCE_STEP',
                   s.type_name || '.' || s.field_name || '#' || CAST(s.ordinal AS VARCHAR)
                     || '[' || CAST(s.position AS VARCHAR) || ']',
                   s.type_name, s.field_name, NULL, s.ordinal, s.position,
                   s.class_name, s.method, d.source_name, d.source_line, d.source_column
              FROM graphitron_field_reference_step s
              JOIN graphitron_field_reference d
                ON d.graph_name = s.graph_name AND d.type_name = s.type_name
               AND d.field_name = s.field_name AND d.ordinal = s.ordinal
             WHERE s.class_name IS NOT NULL AND s.method IS NOT NULL
            UNION ALL
            SELECT s.graph_name, 'ARGUMENT_REFERENCE_STEP',
                   s.type_name || '.' || s.field_name || '(' || s.argument_name || ')#'
                     || CAST(s.ordinal AS VARCHAR) || '[' || CAST(s.position AS VARCHAR) || ']',
                   s.type_name, s.field_name, s.argument_name, s.ordinal, s.position,
                   s.class_name, s.method, d.source_name, d.source_line, d.source_column
              FROM graphitron_argument_reference_step s
              JOIN graphitron_argument_reference d
                ON d.graph_name = s.graph_name AND d.type_name = s.type_name
               AND d.field_name = s.field_name AND d.argument_name = s.argument_name
               AND d.ordinal = s.ordinal
             WHERE s.class_name IS NOT NULL AND s.method IS NOT NULL
            UNION ALL
            SELECT s.graph_name, 'REFERENCE_FOR_STEP',
                   s.type_name || '.' || s.field_name || '#' || CAST(s.ordinal AS VARCHAR)
                     || '[' || CAST(s.position AS VARCHAR) || ']',
                   s.type_name, s.field_name, NULL, s.ordinal, s.position,
                   s.class_name, s.method, d.source_name, d.source_line, d.source_column
              FROM graphitron_reference_for_step s
              JOIN graphitron_reference_for d
                ON d.graph_name = s.graph_name AND d.type_name = s.type_name
               AND d.field_name = s.field_name AND d.ordinal = s.ordinal
             WHERE s.class_name IS NOT NULL AND s.method IS NOT NULL
            UNION ALL
            SELECT s.graph_name, 'ARGUMENT_REFERENCE_FOR_STEP',
                   s.type_name || '.' || s.field_name || '(' || s.argument_name || ')#'
                     || CAST(s.ordinal AS VARCHAR) || '[' || CAST(s.position AS VARCHAR) || ']',
                   s.type_name, s.field_name, s.argument_name, s.ordinal, s.position,
                   s.class_name, s.method, d.source_name, d.source_line, d.source_column
              FROM graphitron_argument_reference_for_step s
              JOIN graphitron_argument_reference_for d
                ON d.graph_name = s.graph_name AND d.type_name = s.type_name
               AND d.field_name = s.field_name AND d.argument_name = s.argument_name
               AND d.ordinal = s.ordinal
             WHERE s.class_name IS NOT NULL AND s.method IS NOT NULL
            """);
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
        dsl.insertInto(GRAPHQL_TYPE_COORDINATE)
            .set(GRAPHQL_TYPE_COORDINATE.GRAPH_NAME, graphName)
            .set(GRAPHQL_TYPE_COORDINATE.TYPE_NAME, typeName)
            .execute();
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
        dsl.insertInto(GRAPHQL_FIELD_COORDINATE)
            .set(GRAPHQL_FIELD_COORDINATE.GRAPH_NAME, graphName)
            .set(GRAPHQL_FIELD_COORDINATE.TYPE_NAME, typeName)
            .set(GRAPHQL_FIELD_COORDINATE.FIELD_NAME, fieldName)
            .execute();
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
     * One field on an input object type, with its wrapping and its place in the type stated
     * outright. An input field differs from the output fields {@link #seedField} spells in two ways
     * a relation reads: its order within the type is a fact consumers fold in, and its nullability
     * is what tells a filter that is always active from one an author may leave unset. Neither can
     * be a default here for that reason.
     *
     * <p>The owning type is seeded as an {@code INPUT_OBJECT} when the case has not seeded it, on
     * {@link #seedDeclaredType}'s idempotent terms; the named type stays the case's to seed, only it
     * knowing the kind.
     *
     * @param itemNonNull the element's non-nullability when {@code isList}, and {@code null}
     *                    otherwise, which the DDL checks rather than tolerates
     */
    public static void seedInputField(DSLContext dsl, String graphName, String typeName,
                                      String fieldName, String namedType, int ordinal,
                                      boolean nonNull, boolean isList, Boolean itemNonNull) {
        seedDeclaredType(dsl, graphName, typeName, "INPUT_OBJECT");
        dsl.insertInto(GRAPHQL_FIELD_COORDINATE)
            .set(GRAPHQL_FIELD_COORDINATE.GRAPH_NAME, graphName)
            .set(GRAPHQL_FIELD_COORDINATE.TYPE_NAME, typeName)
            .set(GRAPHQL_FIELD_COORDINATE.FIELD_NAME, fieldName)
            .execute();
        var element = Boolean.TRUE.equals(itemNonNull) ? namedType + "!" : namedType;
        var listed = isList ? "[" + element + "]" : element;
        dsl.insertInto(GRAPHQL_FIELD)
            .set(GRAPHQL_FIELD.GRAPH_NAME, graphName)
            .set(GRAPHQL_FIELD.TYPE_NAME, typeName)
            .set(GRAPHQL_FIELD.FIELD_NAME, fieldName)
            .set(GRAPHQL_FIELD.ORDINAL, ordinal)
            .set(GRAPHQL_FIELD.DECLARATION_LINE, SEED_LINE)
            .set(GRAPHQL_FIELD.DECLARATION_COLUMN, SEED_COLUMN)
            .set(GRAPHQL_FIELD.TYPE_SDL, nonNull ? listed + "!" : listed)
            .set(GRAPHQL_FIELD.NAMED_TYPE, namedType)
            .set(GRAPHQL_FIELD.NON_NULL, nonNull)
            .set(GRAPHQL_FIELD.IS_LIST, isList)
            .set(GRAPHQL_FIELD.ITEM_NON_NULL, itemNonNull)
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
        seedArgument(dsl, graphName, typeName, fieldName, argumentName, namedType, 0, 2);
    }

    /**
     * The same argument at an ordinal and a line the case names. A field's arguments are a sequence
     * and a relation picking one of several orders on that sequence, so a case about such a pick
     * states both, the position being how the pick is read off the answer.
     */
    public static void seedArgument(DSLContext dsl, String graphName, String typeName,
                                    String fieldName, String argumentName, String namedType,
                                    int ordinal, int sourceLine) {
        dsl.insertInto(GRAPHQL_ARGUMENT_COORDINATE)
            .set(GRAPHQL_ARGUMENT_COORDINATE.GRAPH_NAME, graphName)
            .set(GRAPHQL_ARGUMENT_COORDINATE.TYPE_NAME, typeName)
            .set(GRAPHQL_ARGUMENT_COORDINATE.FIELD_NAME, fieldName)
            .set(GRAPHQL_ARGUMENT_COORDINATE.ARGUMENT_NAME, argumentName)
            .execute();
        dsl.insertInto(GRAPHQL_ARGUMENT)
            .set(GRAPHQL_ARGUMENT.GRAPH_NAME, graphName)
            .set(GRAPHQL_ARGUMENT.TYPE_NAME, typeName)
            .set(GRAPHQL_ARGUMENT.FIELD_NAME, fieldName)
            .set(GRAPHQL_ARGUMENT.ARGUMENT_NAME, argumentName)
            .set(GRAPHQL_ARGUMENT.ORDINAL, ordinal)
            .set(GRAPHQL_ARGUMENT.TYPE_SDL, namedType)
            .set(GRAPHQL_ARGUMENT.NAMED_TYPE, namedType)
            .set(GRAPHQL_ARGUMENT.NON_NULL, false)
            .set(GRAPHQL_ARGUMENT.IS_LIST, false)
            .set(GRAPHQL_ARGUMENT.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHQL_ARGUMENT.SOURCE_LINE, sourceLine)
            .set(GRAPHQL_ARGUMENT.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * The list-shaped sibling of {@link #seedArgument}: {@code [T!]}, non-null elements inside a
     * nullable list. The arm a case reaches for when the relation under test reads the argument's
     * list axis, which the scalar arms above always seed as false.
     */
    public static void seedListArgument(DSLContext dsl, String graphName, String typeName,
                                        String fieldName, String argumentName, String namedType) {
        dsl.insertInto(GRAPHQL_ARGUMENT_COORDINATE)
            .set(GRAPHQL_ARGUMENT_COORDINATE.GRAPH_NAME, graphName)
            .set(GRAPHQL_ARGUMENT_COORDINATE.TYPE_NAME, typeName)
            .set(GRAPHQL_ARGUMENT_COORDINATE.FIELD_NAME, fieldName)
            .set(GRAPHQL_ARGUMENT_COORDINATE.ARGUMENT_NAME, argumentName)
            .execute();
        dsl.insertInto(GRAPHQL_ARGUMENT)
            .set(GRAPHQL_ARGUMENT.GRAPH_NAME, graphName)
            .set(GRAPHQL_ARGUMENT.TYPE_NAME, typeName)
            .set(GRAPHQL_ARGUMENT.FIELD_NAME, fieldName)
            .set(GRAPHQL_ARGUMENT.ARGUMENT_NAME, argumentName)
            .set(GRAPHQL_ARGUMENT.ORDINAL, 0)
            .set(GRAPHQL_ARGUMENT.TYPE_SDL, "[" + namedType + "!]")
            .set(GRAPHQL_ARGUMENT.NAMED_TYPE, namedType)
            .set(GRAPHQL_ARGUMENT.NON_NULL, false)
            .set(GRAPHQL_ARGUMENT.IS_LIST, true)
            .set(GRAPHQL_ARGUMENT.ITEM_NON_NULL, true)
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

    /**
     * A schema block's binding of one operation to a type. The type is seeded as an object if the
     * case has not seeded it, on {@link #seedDeclaredType}'s idempotent terms, so a case whose
     * subject is a root bound to something that is not an object states that kind first.
     *
     * <p>The binding and the conventional name are separable here in a way SDL makes easy to
     * conflate: a type named {@code Query} that nothing binds, and a root binding onto a type named
     * anything else, are both ordinary rows, and a relation that reads the name where it means to
     * read the binding answers differently on the two.
     *
     * @param operation {@code QUERY}, {@code MUTATION} or {@code SUBSCRIPTION}; the DDL accepts no
     *                  other value
     */
    public static void seedRootOperation(DSLContext dsl, String graphName, String operation,
                                         String typeName) {
        seedDeclaredType(dsl, graphName, typeName, "OBJECT");
        dsl.insertInto(GRAPHQL_ROOT_OPERATION)
            .set(GRAPHQL_ROOT_OPERATION.GRAPH_NAME, graphName)
            .set(GRAPHQL_ROOT_OPERATION.OPERATION, operation)
            .set(GRAPHQL_ROOT_OPERATION.TYPE_NAME, typeName)
            .set(GRAPHQL_ROOT_OPERATION.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHQL_ROOT_OPERATION.SOURCE_LINE, 1)
            .set(GRAPHQL_ROOT_OPERATION.SOURCE_COLUMN, 1)
            .execute();
    }

    // ===== The directive applications =====
    //
    // A reference is seeded whole, as an author writes one, and its two parts are filled through
    // the same grammar capture uses, so a seeded row and a captured one cannot disagree about what
    // a part means. A case wanting a partition the split would not produce sets the columns itself.


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
            .set(GRAPHITRON_TABLE.TABLE_REF_NAMESPACE_PART, QualifiedNameGrammar.namespacePart(tableRef))
            .set(GRAPHITRON_TABLE.TABLE_REF_NAME_PART, QualifiedNameGrammar.namePart(tableRef))
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

    /**
     * A {@code @field} application on an argument: the column name the argument binds to, as the
     * author wrote it. The argument-site twin of {@link #seedFieldBinding}, and it states the same
     * thing about resolution: a spelling matching no column is an ordinary row here, the relation
     * that resolves it being the one that declines.
     */
    public static void seedArgumentBinding(DSLContext dsl, String graphName, String typeName,
                                           String fieldName, String argumentName, String nameRef) {
        dsl.insertInto(GRAPHITRON_ARGUMENT_BINDING)
            .set(GRAPHITRON_ARGUMENT_BINDING.GRAPH_NAME, graphName)
            .set(GRAPHITRON_ARGUMENT_BINDING.TYPE_NAME, typeName)
            .set(GRAPHITRON_ARGUMENT_BINDING.FIELD_NAME, fieldName)
            .set(GRAPHITRON_ARGUMENT_BINDING.ARGUMENT_NAME, argumentName)
            .set(GRAPHITRON_ARGUMENT_BINDING.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_ARGUMENT_BINDING.SOURCE_LINE, 2)
            .set(GRAPHITRON_ARGUMENT_BINDING.SOURCE_COLUMN, 3)
            .set(GRAPHITRON_ARGUMENT_BINDING.NAME_REF, nameRef)
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
     * A {@code @splitQuery} application on a field: presence, the marker carrying no argument. The
     * field has to exist already, the application being keyed by the coordinate.
     */
    public static void seedSplitQuery(DSLContext dsl, String graphName, String typeName,
                                      String fieldName) {
        dsl.insertInto(GRAPHITRON_SPLIT_QUERY)
            .set(GRAPHITRON_SPLIT_QUERY.GRAPH_NAME, graphName)
            .set(GRAPHITRON_SPLIT_QUERY.TYPE_NAME, typeName)
            .set(GRAPHITRON_SPLIT_QUERY.FIELD_NAME, fieldName)
            .set(GRAPHITRON_SPLIT_QUERY.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_SPLIT_QUERY.SOURCE_LINE, 2)
            .set(GRAPHITRON_SPLIT_QUERY.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * A {@code @tenantFanOut} application on a field, on {@link #seedSplitQuery}'s terms. The two
     * markers are separate relations rather than one flagged row, so a relation reading both
     * answers with the marker it matched and a case about that reading seeds them apart.
     */
    public static void seedTenantFanOut(DSLContext dsl, String graphName, String typeName,
                                        String fieldName) {
        dsl.insertInto(GRAPHITRON_TENANT_FAN_OUT)
            .set(GRAPHITRON_TENANT_FAN_OUT.GRAPH_NAME, graphName)
            .set(GRAPHITRON_TENANT_FAN_OUT.TYPE_NAME, typeName)
            .set(GRAPHITRON_TENANT_FAN_OUT.FIELD_NAME, fieldName)
            .set(GRAPHITRON_TENANT_FAN_OUT.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_TENANT_FAN_OUT.SOURCE_LINE, 2)
            .set(GRAPHITRON_TENANT_FAN_OUT.SOURCE_COLUMN, 3)
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
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.TABLE_REF_NAMESPACE_PART, QualifiedNameGrammar.namespacePart(tableRef))
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.TABLE_REF_NAME_PART, QualifiedNameGrammar.namePart(tableRef))
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.KEY_REF, keyRef)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.KEY_REF_NAMESPACE_PART, QualifiedNameGrammar.namespacePart(keyRef))
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.KEY_REF_NAME_PART, QualifiedNameGrammar.namePart(keyRef))
            .execute();
    }

    /**
     * One element of a path spelling a condition method instead of a hop: the class and the method
     * as the author wrote them, and neither a key nor a table. The route such an element takes comes
     * off the method's own signature, so a case using this arm seeds the census rows that signature
     * decomposes into, {@link #seedConditionMethod} being the one line that does.
     */
    public static void seedFieldReferenceCall(DSLContext dsl, String graphName, String typeName,
                                              String fieldName, int ordinal, int position,
                                              String className, String method) {
        seedFieldReferenceElement(dsl, graphName, typeName, fieldName, ordinal, position,
            null, null, className, method);
    }

    /**
     * One element spelling every form at once, for a case whose subject is a combination the two
     * arms above cannot state: a key or a table carrying a condition beside it, which is that hop's
     * filter rather than its route and is therefore a different authored form from a bare condition.
     * Any of the four may be null.
     */
    public static void seedFieldReferenceElement(DSLContext dsl, String graphName, String typeName,
                                                 String fieldName, int ordinal, int position,
                                                 String tableRef, String keyRef,
                                                 String className, String method) {
        dsl.insertInto(GRAPHITRON_FIELD_REFERENCE_STEP)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.GRAPH_NAME, graphName)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.TYPE_NAME, typeName)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.FIELD_NAME, fieldName)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.ORDINAL, ordinal)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.POSITION, position)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.TABLE_REF, tableRef)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.TABLE_REF_NAMESPACE_PART, QualifiedNameGrammar.namespacePart(tableRef))
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.TABLE_REF_NAME_PART, QualifiedNameGrammar.namePart(tableRef))
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.KEY_REF, keyRef)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.KEY_REF_NAMESPACE_PART, QualifiedNameGrammar.namespacePart(keyRef))
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.KEY_REF_NAME_PART, QualifiedNameGrammar.namePart(keyRef))
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.CLASS_NAME, className)
            .set(GRAPHITRON_FIELD_REFERENCE_STEP.METHOD, method)
            .execute();
    }

    /**
     * One {@code @reference} application on an argument that already exists. Repetition on an
     * argument is a stated conflict rather than a chain, so a case seeding a second ordinal is
     * stating that shape deliberately.
     */
    public static void seedArgumentReference(DSLContext dsl, String graphName, String typeName,
                                             String fieldName, String argumentName, int ordinal) {
        dsl.insertInto(GRAPHITRON_ARGUMENT_REFERENCE)
            .set(GRAPHITRON_ARGUMENT_REFERENCE.GRAPH_NAME, graphName)
            .set(GRAPHITRON_ARGUMENT_REFERENCE.TYPE_NAME, typeName)
            .set(GRAPHITRON_ARGUMENT_REFERENCE.FIELD_NAME, fieldName)
            .set(GRAPHITRON_ARGUMENT_REFERENCE.ARGUMENT_NAME, argumentName)
            .set(GRAPHITRON_ARGUMENT_REFERENCE.ORDINAL, ordinal)
            .set(GRAPHITRON_ARGUMENT_REFERENCE.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_ARGUMENT_REFERENCE.SOURCE_LINE, 2)
            .set(GRAPHITRON_ARGUMENT_REFERENCE.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * One element of an argument-site path, spelling a key, a table, or both, exactly as the author
     * wrote them. The argument-site twin of {@link #seedFieldReferenceStep}, and the qualifier split
     * is capture's on both sides, so a case states one spelling and the resolution under assertion
     * reads the parts.
     */
    public static void seedArgumentReferenceStep(DSLContext dsl, String graphName, String typeName,
                                                 String fieldName, String argumentName,
                                                 int ordinal, int position,
                                                 String tableRef, String keyRef) {
        dsl.insertInto(GRAPHITRON_ARGUMENT_REFERENCE_STEP)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.GRAPH_NAME, graphName)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.TYPE_NAME, typeName)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.FIELD_NAME, fieldName)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.ARGUMENT_NAME, argumentName)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.ORDINAL, ordinal)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.POSITION, position)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.TABLE_REF, tableRef)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.TABLE_REF_NAMESPACE_PART, QualifiedNameGrammar.namespacePart(tableRef))
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.TABLE_REF_NAME_PART, QualifiedNameGrammar.namePart(tableRef))
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.KEY_REF, keyRef)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.KEY_REF_NAMESPACE_PART, QualifiedNameGrammar.namespacePart(keyRef))
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.KEY_REF_NAME_PART, QualifiedNameGrammar.namePart(keyRef))
            .execute();
    }

    /**
     * One element of an argument-site path spelling a condition method instead of a hop: neither a
     * key nor a table, so the route comes off the method's signature and a case using this arm seeds
     * that signature with {@link #seedConditionMethod}.
     */
    public static void seedArgumentReferenceCall(DSLContext dsl, String graphName, String typeName,
                                                 String fieldName, String argumentName,
                                                 int ordinal, int position,
                                                 String className, String method) {
        seedArgumentReferenceElement(dsl, graphName, typeName, fieldName, argumentName, ordinal,
            position, null, null, className, method);
    }

    /**
     * The argument-site twin of {@link #seedFieldReferenceElement}: one element spelling every form
     * at once, for the combination the two arms above cannot state. Any of the four may be null.
     */
    public static void seedArgumentReferenceElement(DSLContext dsl, String graphName,
                                                    String typeName, String fieldName,
                                                    String argumentName, int ordinal, int position,
                                                    String tableRef, String keyRef,
                                                    String className, String method) {
        dsl.insertInto(GRAPHITRON_ARGUMENT_REFERENCE_STEP)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.GRAPH_NAME, graphName)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.TYPE_NAME, typeName)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.FIELD_NAME, fieldName)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.ARGUMENT_NAME, argumentName)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.ORDINAL, ordinal)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.POSITION, position)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.TABLE_REF, tableRef)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.TABLE_REF_NAMESPACE_PART, QualifiedNameGrammar.namespacePart(tableRef))
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.TABLE_REF_NAME_PART, QualifiedNameGrammar.namePart(tableRef))
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.KEY_REF, keyRef)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.KEY_REF_NAMESPACE_PART, QualifiedNameGrammar.namespacePart(keyRef))
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.KEY_REF_NAME_PART, QualifiedNameGrammar.namePart(keyRef))
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.CLASS_NAME, className)
            .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.METHOD, method)
            .execute();
    }

    /**
     * A {@code @condition} application on a field or an input field, stated by its cascade flag
     * alone. The reference is left unnamed, which a relation reading only the flag does not miss;
     * a case whose subject is the reference states it with the other overload.
     *
     * @param override as the author wrote it, so {@code null} is the omitted spelling and a
     *                 relation treating it as the {@code false} it defaults to has to say so
     */
    public static void seedFieldCondition(DSLContext dsl, String graphName, String typeName,
                                          String fieldName, Boolean override) {
        seedFieldCondition(dsl, graphName, typeName, fieldName, null, null, override);
    }

    /** The same application with the reference the author wrote, on {@link #seedService}'s terms. */
    public static void seedFieldCondition(DSLContext dsl, String graphName, String typeName,
                                          String fieldName, String className, String method,
                                          Boolean override) {
        dsl.insertInto(GRAPHITRON_FIELD_CONDITION)
            .set(GRAPHITRON_FIELD_CONDITION.GRAPH_NAME, graphName)
            .set(GRAPHITRON_FIELD_CONDITION.TYPE_NAME, typeName)
            .set(GRAPHITRON_FIELD_CONDITION.FIELD_NAME, fieldName)
            .set(GRAPHITRON_FIELD_CONDITION.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_FIELD_CONDITION.SOURCE_LINE, 2)
            .set(GRAPHITRON_FIELD_CONDITION.SOURCE_COLUMN, 3)
            .set(GRAPHITRON_FIELD_CONDITION.CLASS_NAME, className)
            .set(GRAPHITRON_FIELD_CONDITION.METHOD, method)
            .set(GRAPHITRON_FIELD_CONDITION.OVERRIDE, override)
            .execute();
    }

    /**
     * The same directive at the argument site, which is its own relation with its own key rather
     * than a column on the field row. A relation reading both answers with the site it matched, so
     * a case about that reading seeds the two separately and they stay distinguishable.
     */
    public static void seedArgumentCondition(DSLContext dsl, String graphName, String typeName,
                                             String fieldName, String argumentName, Boolean override) {
        seedArgumentCondition(dsl, graphName, typeName, fieldName, argumentName, null, null, override);
    }

    /** The argument-site application with the reference the author wrote. */
    public static void seedArgumentCondition(DSLContext dsl, String graphName, String typeName,
                                             String fieldName, String argumentName, String className,
                                             String method, Boolean override) {
        dsl.insertInto(GRAPHITRON_ARGUMENT_CONDITION)
            .set(GRAPHITRON_ARGUMENT_CONDITION.GRAPH_NAME, graphName)
            .set(GRAPHITRON_ARGUMENT_CONDITION.TYPE_NAME, typeName)
            .set(GRAPHITRON_ARGUMENT_CONDITION.FIELD_NAME, fieldName)
            .set(GRAPHITRON_ARGUMENT_CONDITION.ARGUMENT_NAME, argumentName)
            .set(GRAPHITRON_ARGUMENT_CONDITION.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_ARGUMENT_CONDITION.SOURCE_LINE, 2)
            .set(GRAPHITRON_ARGUMENT_CONDITION.SOURCE_COLUMN, 3)
            .set(GRAPHITRON_ARGUMENT_CONDITION.CLASS_NAME, className)
            .set(GRAPHITRON_ARGUMENT_CONDITION.METHOD, method)
            .set(GRAPHITRON_ARGUMENT_CONDITION.OVERRIDE, override)
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

    // ===== The input surface under a use site =====

    /** One input-field step of an occurrence path: where the field is declared, and where it leads. */
    public record OccurrenceStep(String containerTypeName, String fieldName, String namedType) {}

    /**
     * An occurrence of the input surface under one use site, with every prefix present as its own
     * row and each prefix's steps under it, which is the shape the capture-cadence writer leaves
     * behind. Stated rather than expanded from types, because a case about a keying over this
     * relation is a case about these rows and not about the descent that produced them; a case that
     * wants the descent's own rule tested is beside the writer.
     *
     * <p>The serialized key is built the way the writer builds it, {@code Type.field(argument)}
     * followed by one {@code /field} per step. No reader parses it; the shape matters only so two
     * occurrences cannot collide.
     *
     * <p>A prefix already present is left alone rather than inserted twice, so an input type
     * branching under one argument is a call per branch. Prefixes are shared by the relation's own
     * invariant, not by this helper's convenience: two occurrences below one argument descend
     * through the same rows, and a seeder that insisted on writing each prefix once could not state
     * a branch at all.
     */
    public static void seedOccurrencePath(DSLContext dsl, String graphName, String rootTypeName,
                                         String rootFieldName, String rootArgumentName,
                                         String rootInputType, OccurrenceStep... steps) {
        var root = rootTypeName + "." + rootFieldName + "(" + rootArgumentName + ")";
        for (int depth = 0; depth <= steps.length; depth++) {
            var path = new StringBuilder(root);
            for (int i = 0; i < depth; i++) {
                path.append('/').append(steps[i].fieldName());
            }
            if (dsl.fetchExists(INTENT_INPUT_OCCURRENCE_PATH,
                    INTENT_INPUT_OCCURRENCE_PATH.GRAPH_NAME.eq(graphName)
                        .and(INTENT_INPUT_OCCURRENCE_PATH.PATH.eq(path.toString())))) {
                continue;
            }
            dsl.insertInto(INTENT_INPUT_OCCURRENCE_PATH)
                .set(INTENT_INPUT_OCCURRENCE_PATH.GRAPH_NAME, graphName)
                .set(INTENT_INPUT_OCCURRENCE_PATH.PATH, path.toString())
                .set(INTENT_INPUT_OCCURRENCE_PATH.ROOT_TYPE_NAME, rootTypeName)
                .set(INTENT_INPUT_OCCURRENCE_PATH.ROOT_FIELD_NAME, rootFieldName)
                .set(INTENT_INPUT_OCCURRENCE_PATH.ROOT_ARGUMENT_NAME, rootArgumentName)
                .set(INTENT_INPUT_OCCURRENCE_PATH.ROOT_INPUT_TYPE, rootInputType)
                .set(INTENT_INPUT_OCCURRENCE_PATH.LEAF_NAMED_TYPE,
                    depth == 0 ? rootInputType : steps[depth - 1].namedType())
                .set(INTENT_INPUT_OCCURRENCE_PATH.DEPTH, depth)
                .execute();
            for (int ordinal = 1; ordinal <= depth; ordinal++) {
                var step = steps[ordinal - 1];
                dsl.insertInto(INTENT_INPUT_OCCURRENCE_PATH_STEP)
                    .set(INTENT_INPUT_OCCURRENCE_PATH_STEP.GRAPH_NAME, graphName)
                    .set(INTENT_INPUT_OCCURRENCE_PATH_STEP.PATH, path.toString())
                    .set(INTENT_INPUT_OCCURRENCE_PATH_STEP.ORDINAL, ordinal)
                    .set(INTENT_INPUT_OCCURRENCE_PATH_STEP.CONTAINER_TYPE_NAME,
                        step.containerTypeName())
                    .set(INTENT_INPUT_OCCURRENCE_PATH_STEP.FIELD_NAME, step.fieldName())
                    .set(INTENT_INPUT_OCCURRENCE_PATH_STEP.NAMED_TYPE, step.namedType())
                    .execute();
            }
        }
    }

    // ===== argMapping pairs, one seeder per site =====

    /**
     * The one relation an argMapping pair lands in, whatever site spelled it. Every seeder above
     * ends here because the schema has one pair relation and not nine: a site that carried nothing
     * beyond the shared pair earned no table of its own, so the site is this row's discriminator.
     *
     * <p>The seeders each ensure the owning directive exists before calling this, which is what
     * keeps the reference from a pair back to its site true. The collapse gave up the foreign key
     * that used to enforce it, a foreign key not being able to span the nine parents the
     * discriminator chooses between, so a fixture holds the invariant the way capture does, by
     * writing the pair inside the branch that has just established the site.
     */
    private static void pair(DSLContext dsl, String graphName, String site, String typeName,
                             String fieldName, String argumentName, Integer ordinal,
                             Integer stepPosition, int position, String paramName,
                             String argumentPath) {
        var location = siteLocation(dsl, graphName, site, typeName, fieldName, argumentName, ordinal);
        dsl.insertInto(GRAPHITRON_ARG_MAPPING_PAIR)
            .set(GRAPHITRON_ARG_MAPPING_PAIR.GRAPH_NAME, graphName)
            .set(GRAPHITRON_ARG_MAPPING_PAIR.SITE, site)
            .set(GRAPHITRON_ARG_MAPPING_PAIR.USE_SITE,
                useSite(typeName, fieldName, argumentName, ordinal, stepPosition))
            .set(GRAPHITRON_ARG_MAPPING_PAIR.TYPE_NAME, typeName)
            .set(GRAPHITRON_ARG_MAPPING_PAIR.FIELD_NAME, fieldName)
            .set(GRAPHITRON_ARG_MAPPING_PAIR.ARGUMENT_NAME, argumentName)
            .set(GRAPHITRON_ARG_MAPPING_PAIR.ORDINAL, ordinal)
            .set(GRAPHITRON_ARG_MAPPING_PAIR.STEP_POSITION, stepPosition)
            .set(GRAPHITRON_ARG_MAPPING_PAIR.POSITION, position)
            .set(GRAPHITRON_ARG_MAPPING_PAIR.PARAM_NAME, paramName)
            .set(GRAPHITRON_ARG_MAPPING_PAIR.ARGUMENT_PATH, argumentPath)
            // Derived here the way capture derives them, from the path text and the site, so a
            // fixture cannot state a head the same path would not produce.
            .set(GRAPHITRON_ARG_MAPPING_PAIR.HEAD_SEGMENT,
                argumentPath.indexOf('.') < 0 ? argumentPath
                    : argumentPath.substring(0, argumentPath.indexOf('.')))
            .set(GRAPHITRON_ARG_MAPPING_PAIR.HEAD_KIND,
                "INPUT_FIELD_CONDITION".equals(site) ? "INPUT_FIELD" : "ARGUMENT")
            .set(GRAPHITRON_ARG_MAPPING_PAIR.CANDIDATE_ORIGIN,
                "INPUT_FIELD_CONDITION".equals(site)
                    ? typeName + "." + head(argumentPath)
                    : typeName + "." + fieldName + "(" + head(argumentPath) + ")")
            .set(GRAPHITRON_ARG_MAPPING_PAIR.CANDIDATE_PATH,
                argumentPath.indexOf('.') < 0 ? ""
                    : argumentPath.substring(argumentPath.indexOf('.') + 1))
            .set(GRAPHITRON_ARG_MAPPING_PAIR.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_ARG_MAPPING_PAIR.SOURCE_LINE, location.value1())
            .set(GRAPHITRON_ARG_MAPPING_PAIR.SOURCE_COLUMN, location.value2())
            .execute();
    }

    /** The head of a written path, which is the element the origin's spelling names. */
    private static String head(String argumentPath) {
        int dot = argumentPath.indexOf('.');
        return dot < 0 ? argumentPath : argumentPath.substring(0, dot);
    }

    /**
     * Where the owning directive application was written, which is where a pair of its argMapping
     * was written too. Read from the application rather than defaulted, because a repeatable
     * directive's second application sits on its own line and a diagnostic about its argMapping
     * has to point there; capture takes the same location from the same place.
     *
     * <p>The switch is the shape of what a foreign key could not be. One relation holds the pairs
     * of nine kinds of site, so the site column is what says which application to ask, and the
     * seeders above have each established that application before calling in.
     */
    private static Record2<Integer, Integer> siteLocation(
            DSLContext dsl, String graphName, String site, String typeName, String fieldName,
            String argumentName, Integer ordinal) {
        var r = switch (site) {
            case "ROUTINE" -> dsl
                .select(GRAPHITRON_ROUTINE.SOURCE_LINE, GRAPHITRON_ROUTINE.SOURCE_COLUMN)
                .from(GRAPHITRON_ROUTINE)
                .where(GRAPHITRON_ROUTINE.GRAPH_NAME.eq(graphName),
                    GRAPHITRON_ROUTINE.TYPE_NAME.eq(typeName),
                    GRAPHITRON_ROUTINE.FIELD_NAME.eq(fieldName),
                    GRAPHITRON_ROUTINE.ORDINAL.eq(ordinal))
                .fetchOne();
            case "SERVICE" -> dsl
                .select(GRAPHITRON_SERVICE.SOURCE_LINE, GRAPHITRON_SERVICE.SOURCE_COLUMN)
                .from(GRAPHITRON_SERVICE)
                .where(GRAPHITRON_SERVICE.GRAPH_NAME.eq(graphName),
                    GRAPHITRON_SERVICE.TYPE_NAME.eq(typeName),
                    GRAPHITRON_SERVICE.FIELD_NAME.eq(fieldName))
                .fetchOne();
            case "FIELD_CONDITION", "INPUT_FIELD_CONDITION" -> dsl
                .select(GRAPHITRON_FIELD_CONDITION.SOURCE_LINE,
                    GRAPHITRON_FIELD_CONDITION.SOURCE_COLUMN)
                .from(GRAPHITRON_FIELD_CONDITION)
                .where(GRAPHITRON_FIELD_CONDITION.GRAPH_NAME.eq(graphName),
                    GRAPHITRON_FIELD_CONDITION.TYPE_NAME.eq(typeName),
                    GRAPHITRON_FIELD_CONDITION.FIELD_NAME.eq(fieldName))
                .fetchOne();
            case "ARGUMENT_CONDITION" -> dsl
                .select(GRAPHITRON_ARGUMENT_CONDITION.SOURCE_LINE,
                    GRAPHITRON_ARGUMENT_CONDITION.SOURCE_COLUMN)
                .from(GRAPHITRON_ARGUMENT_CONDITION)
                .where(GRAPHITRON_ARGUMENT_CONDITION.GRAPH_NAME.eq(graphName),
                    GRAPHITRON_ARGUMENT_CONDITION.TYPE_NAME.eq(typeName),
                    GRAPHITRON_ARGUMENT_CONDITION.FIELD_NAME.eq(fieldName),
                    GRAPHITRON_ARGUMENT_CONDITION.ARGUMENT_NAME.eq(argumentName))
                .fetchOne();
            case "FIELD_REFERENCE_STEP" -> dsl
                .select(GRAPHITRON_FIELD_REFERENCE.SOURCE_LINE,
                    GRAPHITRON_FIELD_REFERENCE.SOURCE_COLUMN)
                .from(GRAPHITRON_FIELD_REFERENCE)
                .where(GRAPHITRON_FIELD_REFERENCE.GRAPH_NAME.eq(graphName),
                    GRAPHITRON_FIELD_REFERENCE.TYPE_NAME.eq(typeName),
                    GRAPHITRON_FIELD_REFERENCE.FIELD_NAME.eq(fieldName),
                    GRAPHITRON_FIELD_REFERENCE.ORDINAL.eq(ordinal))
                .fetchOne();
            case "ARGUMENT_REFERENCE_STEP" -> dsl
                .select(GRAPHITRON_ARGUMENT_REFERENCE.SOURCE_LINE,
                    GRAPHITRON_ARGUMENT_REFERENCE.SOURCE_COLUMN)
                .from(GRAPHITRON_ARGUMENT_REFERENCE)
                .where(GRAPHITRON_ARGUMENT_REFERENCE.GRAPH_NAME.eq(graphName),
                    GRAPHITRON_ARGUMENT_REFERENCE.TYPE_NAME.eq(typeName),
                    GRAPHITRON_ARGUMENT_REFERENCE.FIELD_NAME.eq(fieldName),
                    GRAPHITRON_ARGUMENT_REFERENCE.ARGUMENT_NAME.eq(argumentName),
                    GRAPHITRON_ARGUMENT_REFERENCE.ORDINAL.eq(ordinal))
                .fetchOne();
            case "REFERENCE_FOR_STEP" -> dsl
                .select(GRAPHITRON_REFERENCE_FOR.SOURCE_LINE,
                    GRAPHITRON_REFERENCE_FOR.SOURCE_COLUMN)
                .from(GRAPHITRON_REFERENCE_FOR)
                .where(GRAPHITRON_REFERENCE_FOR.GRAPH_NAME.eq(graphName),
                    GRAPHITRON_REFERENCE_FOR.TYPE_NAME.eq(typeName),
                    GRAPHITRON_REFERENCE_FOR.FIELD_NAME.eq(fieldName),
                    GRAPHITRON_REFERENCE_FOR.ORDINAL.eq(ordinal))
                .fetchOne();
            default -> throw new IllegalArgumentException("no application relation for site " + site);
        };
        if (r == null) {
            throw new IllegalStateException(
                "no " + site + " application under the pair at " + typeName + "." + fieldName
                    + "; a seeder must establish the site before writing its pairs");
        }
        return r;
    }

    /**
     * The site spelled in its own grammar, which is what keys the pair relation where the
     * decomposed columns beside it cannot, three of them being null on the sites that have no such
     * part. Must agree with the spelling capture writes, or a fixture and a captured store would
     * key the same site two ways.
     */
    private static String useSite(String typeName, String fieldName, String argumentName,
                                  Integer ordinal, Integer stepPosition) {
        var spelling = new StringBuilder(typeName).append('.').append(fieldName);
        if (argumentName != null) {
            spelling.append('(').append(argumentName).append(')');
        }
        if (ordinal != null) {
            spelling.append('#').append(ordinal);
        }
        if (stepPosition != null) {
            spelling.append('[').append(stepPosition).append(']');
        }
        return spelling.toString();
    }

    /**
     * One pair of a {@code @routine}'s {@code argMapping}, with the application under it. The
     * directive is repeatable, so the ordinal is the case's to state: it is half of what tells two
     * applications' pairs apart.
     */
    public static void seedRoutineArgMappingPair(DSLContext dsl, String graphName, String typeName,
                                                 String fieldName, int ordinal, int position,
                                                 String paramName, String argumentPath) {
        if (!dsl.fetchExists(GRAPHITRON_ROUTINE, GRAPHITRON_ROUTINE.GRAPH_NAME.eq(graphName)
                .and(GRAPHITRON_ROUTINE.TYPE_NAME.eq(typeName))
                .and(GRAPHITRON_ROUTINE.FIELD_NAME.eq(fieldName))
                .and(GRAPHITRON_ROUTINE.ORDINAL.eq(ordinal)))) {
            seedRoutine(dsl, graphName, typeName, fieldName, ordinal, "Routines.someRoutine", 2);
        }
        pair(dsl, graphName, "ROUTINE", typeName, fieldName, null, ordinal, null,
            position, paramName, argumentPath);
    }

    /** One pair of a {@code @service}'s {@code argMapping}, with the application under it. */
    public static void seedServiceArgMappingPair(DSLContext dsl, String graphName, String typeName,
                                                 String fieldName, int position, String paramName,
                                                 String argumentPath) {
        if (!dsl.fetchExists(GRAPHITRON_SERVICE, GRAPHITRON_SERVICE.GRAPH_NAME.eq(graphName)
                .and(GRAPHITRON_SERVICE.TYPE_NAME.eq(typeName))
                .and(GRAPHITRON_SERVICE.FIELD_NAME.eq(fieldName)))) {
            seedService(dsl, graphName, typeName, fieldName, "no.example.Svc", "get");
        }
        pair(dsl, graphName, "SERVICE", typeName, fieldName, null, null, null,
            position, paramName, argumentPath);
    }

    /**
     * One pair of a field-site {@code @condition}'s {@code argMapping}, with the application under
     * it. The relation is a shared coordinate and the owning type's kind is what tells an
     * output-field site from an input-field one, so a case seeds the type it means first: this
     * helper does not seed one.
     */
    public static void seedFieldConditionArgMappingPair(DSLContext dsl, String graphName,
                                                        String typeName, String fieldName,
                                                        int position, String paramName,
                                                        String argumentPath) {
        if (!dsl.fetchExists(GRAPHITRON_FIELD_CONDITION,
                GRAPHITRON_FIELD_CONDITION.GRAPH_NAME.eq(graphName)
                    .and(GRAPHITRON_FIELD_CONDITION.TYPE_NAME.eq(typeName))
                    .and(GRAPHITRON_FIELD_CONDITION.FIELD_NAME.eq(fieldName)))) {
            seedFieldCondition(dsl, graphName, typeName, fieldName, "no.example.Cond", "apply", false);
        }
        boolean onInput = dsl.fetchExists(GRAPHQL_TYPE, GRAPHQL_TYPE.GRAPH_NAME.eq(graphName)
            .and(GRAPHQL_TYPE.TYPE_NAME.eq(typeName))
            .and(GRAPHQL_TYPE.KIND.eq("INPUT_OBJECT")));
        pair(dsl, graphName, onInput ? "INPUT_FIELD_CONDITION" : "FIELD_CONDITION",
            typeName, fieldName, null, null, null, position, paramName, argumentPath);
    }

    /**
     * One pair of an argument-site {@code @condition}'s {@code argMapping}, with the application and
     * the argument it sits on under it.
     */
    public static void seedArgumentConditionArgMappingPair(DSLContext dsl, String graphName,
                                                           String typeName, String fieldName,
                                                           String argumentName, int position,
                                                           String paramName, String argumentPath) {
        if (!dsl.fetchExists(GRAPHQL_ARGUMENT, GRAPHQL_ARGUMENT.GRAPH_NAME.eq(graphName)
                .and(GRAPHQL_ARGUMENT.TYPE_NAME.eq(typeName))
                .and(GRAPHQL_ARGUMENT.FIELD_NAME.eq(fieldName))
                .and(GRAPHQL_ARGUMENT.ARGUMENT_NAME.eq(argumentName)))) {
            seedArgument(dsl, graphName, typeName, fieldName, argumentName, "String");
        }
        if (!dsl.fetchExists(GRAPHITRON_ARGUMENT_CONDITION,
                GRAPHITRON_ARGUMENT_CONDITION.GRAPH_NAME.eq(graphName)
                    .and(GRAPHITRON_ARGUMENT_CONDITION.TYPE_NAME.eq(typeName))
                    .and(GRAPHITRON_ARGUMENT_CONDITION.FIELD_NAME.eq(fieldName))
                    .and(GRAPHITRON_ARGUMENT_CONDITION.ARGUMENT_NAME.eq(argumentName)))) {
            seedArgumentCondition(dsl, graphName, typeName, fieldName, argumentName,
                "no.example.Cond", "apply", false);
        }
        pair(dsl, graphName, "ARGUMENT_CONDITION", typeName, fieldName, argumentName, null, null,
            position, paramName, argumentPath);
    }

    /**
     * One pair of a field-site {@code @reference} step condition's {@code argMapping}, with the
     * application and the step under it.
     */
    public static void seedFieldReferenceStepArgMappingPair(DSLContext dsl, String graphName,
                                                            String typeName, String fieldName,
                                                            int ordinal, int stepPosition,
                                                            int position, String paramName,
                                                            String argumentPath) {
        if (!dsl.fetchExists(GRAPHITRON_FIELD_REFERENCE_STEP,
                GRAPHITRON_FIELD_REFERENCE_STEP.GRAPH_NAME.eq(graphName)
                    .and(GRAPHITRON_FIELD_REFERENCE_STEP.TYPE_NAME.eq(typeName))
                    .and(GRAPHITRON_FIELD_REFERENCE_STEP.FIELD_NAME.eq(fieldName))
                    .and(GRAPHITRON_FIELD_REFERENCE_STEP.ORDINAL.eq(ordinal))
                    .and(GRAPHITRON_FIELD_REFERENCE_STEP.POSITION.eq(stepPosition)))) {
            seedFieldReference(dsl, graphName, typeName, fieldName, ordinal);
            seedFieldReferenceStep(dsl, graphName, typeName, fieldName, ordinal, stepPosition,
                null, null);
        }
        pair(dsl, graphName, "FIELD_REFERENCE_STEP", typeName, fieldName, null, ordinal,
            stepPosition, position, paramName, argumentPath);
    }

    /**
     * One pair of an argument-site {@code @reference} step condition's {@code argMapping}, with the
     * argument, the application and the step under it.
     */
    public static void seedArgumentReferenceStepArgMappingPair(DSLContext dsl, String graphName,
                                                               String typeName, String fieldName,
                                                               String argumentName, int ordinal,
                                                               int stepPosition, int position,
                                                               String paramName,
                                                               String argumentPath) {
        if (!dsl.fetchExists(GRAPHQL_ARGUMENT, GRAPHQL_ARGUMENT.GRAPH_NAME.eq(graphName)
                .and(GRAPHQL_ARGUMENT.TYPE_NAME.eq(typeName))
                .and(GRAPHQL_ARGUMENT.FIELD_NAME.eq(fieldName))
                .and(GRAPHQL_ARGUMENT.ARGUMENT_NAME.eq(argumentName)))) {
            seedArgument(dsl, graphName, typeName, fieldName, argumentName, "String");
        }
        if (!dsl.fetchExists(GRAPHITRON_ARGUMENT_REFERENCE_STEP,
                GRAPHITRON_ARGUMENT_REFERENCE_STEP.GRAPH_NAME.eq(graphName)
                    .and(GRAPHITRON_ARGUMENT_REFERENCE_STEP.TYPE_NAME.eq(typeName))
                    .and(GRAPHITRON_ARGUMENT_REFERENCE_STEP.FIELD_NAME.eq(fieldName))
                    .and(GRAPHITRON_ARGUMENT_REFERENCE_STEP.ARGUMENT_NAME.eq(argumentName))
                    .and(GRAPHITRON_ARGUMENT_REFERENCE_STEP.ORDINAL.eq(ordinal))
                    .and(GRAPHITRON_ARGUMENT_REFERENCE_STEP.POSITION.eq(stepPosition)))) {
            dsl.insertInto(GRAPHITRON_ARGUMENT_REFERENCE)
                .set(GRAPHITRON_ARGUMENT_REFERENCE.GRAPH_NAME, graphName)
                .set(GRAPHITRON_ARGUMENT_REFERENCE.TYPE_NAME, typeName)
                .set(GRAPHITRON_ARGUMENT_REFERENCE.FIELD_NAME, fieldName)
                .set(GRAPHITRON_ARGUMENT_REFERENCE.ARGUMENT_NAME, argumentName)
                .set(GRAPHITRON_ARGUMENT_REFERENCE.ORDINAL, ordinal)
                .set(GRAPHITRON_ARGUMENT_REFERENCE.SOURCE_NAME, SEED_SOURCE)
                .set(GRAPHITRON_ARGUMENT_REFERENCE.SOURCE_LINE, 2)
                .set(GRAPHITRON_ARGUMENT_REFERENCE.SOURCE_COLUMN, 3)
                .onDuplicateKeyIgnore()
                .execute();
            dsl.insertInto(GRAPHITRON_ARGUMENT_REFERENCE_STEP)
                .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.GRAPH_NAME, graphName)
                .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.TYPE_NAME, typeName)
                .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.FIELD_NAME, fieldName)
                .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.ARGUMENT_NAME, argumentName)
                .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.ORDINAL, ordinal)
                .set(GRAPHITRON_ARGUMENT_REFERENCE_STEP.POSITION, stepPosition)
                .execute();
        }
        pair(dsl, graphName, "ARGUMENT_REFERENCE_STEP", typeName, fieldName, argumentName,
            ordinal, stepPosition, position, paramName, argumentPath);
    }

    /**
     * One pair of a {@code @referenceFor} step condition's {@code argMapping}, with the application
     * and the step under it.
     */
    public static void seedReferenceForStepArgMappingPair(DSLContext dsl, String graphName,
                                                          String typeName, String fieldName,
                                                          int ordinal, int stepPosition,
                                                          int position, String paramName,
                                                          String argumentPath) {
        if (!dsl.fetchExists(GRAPHITRON_REFERENCE_FOR_STEP,
                GRAPHITRON_REFERENCE_FOR_STEP.GRAPH_NAME.eq(graphName)
                    .and(GRAPHITRON_REFERENCE_FOR_STEP.TYPE_NAME.eq(typeName))
                    .and(GRAPHITRON_REFERENCE_FOR_STEP.FIELD_NAME.eq(fieldName))
                    .and(GRAPHITRON_REFERENCE_FOR_STEP.ORDINAL.eq(ordinal))
                    .and(GRAPHITRON_REFERENCE_FOR_STEP.POSITION.eq(stepPosition)))) {
            dsl.insertInto(GRAPHITRON_REFERENCE_FOR)
                .set(GRAPHITRON_REFERENCE_FOR.GRAPH_NAME, graphName)
                .set(GRAPHITRON_REFERENCE_FOR.TYPE_NAME, typeName)
                .set(GRAPHITRON_REFERENCE_FOR.FIELD_NAME, fieldName)
                .set(GRAPHITRON_REFERENCE_FOR.ORDINAL, ordinal)
                .set(GRAPHITRON_REFERENCE_FOR.SOURCE_NAME, SEED_SOURCE)
                .set(GRAPHITRON_REFERENCE_FOR.SOURCE_LINE, 2)
                .set(GRAPHITRON_REFERENCE_FOR.SOURCE_COLUMN, 3)
                .set(GRAPHITRON_REFERENCE_FOR.PARTICIPANT_TYPE_REF, "Other")
                .onDuplicateKeyIgnore()
                .execute();
            dsl.insertInto(GRAPHITRON_REFERENCE_FOR_STEP)
                .set(GRAPHITRON_REFERENCE_FOR_STEP.GRAPH_NAME, graphName)
                .set(GRAPHITRON_REFERENCE_FOR_STEP.TYPE_NAME, typeName)
                .set(GRAPHITRON_REFERENCE_FOR_STEP.FIELD_NAME, fieldName)
                .set(GRAPHITRON_REFERENCE_FOR_STEP.ORDINAL, ordinal)
                .set(GRAPHITRON_REFERENCE_FOR_STEP.POSITION, stepPosition)
                .execute();
        }
        pair(dsl, graphName, "REFERENCE_FOR_STEP", typeName, fieldName, null, ordinal,
            stepPosition, position, paramName, argumentPath);
    }

    /**
     * The segment decomposition capture writes beside a pair, one row per dot-separated segment in
     * written order. Stated by splitting the path the same way the lexer does, since the invariant
     * the relation carries is that the segments in order rejoin the path exactly.
     */
    public static void seedArgumentPathSegments(DSLContext dsl, String graphName, String typeName,
                                                String fieldName, String argumentPath) {
        var segments = argumentPath.split("\\.", -1);
        for (int position = 0; position < segments.length; position++) {
            dsl.insertInto(GRAPHITRON_ARGUMENT_PATH_SEGMENT)
                .set(GRAPHITRON_ARGUMENT_PATH_SEGMENT.GRAPH_NAME, graphName)
                .set(GRAPHITRON_ARGUMENT_PATH_SEGMENT.TYPE_NAME, typeName)
                .set(GRAPHITRON_ARGUMENT_PATH_SEGMENT.FIELD_NAME, fieldName)
                .set(GRAPHITRON_ARGUMENT_PATH_SEGMENT.ARGUMENT_PATH, argumentPath)
                .set(GRAPHITRON_ARGUMENT_PATH_SEGMENT.POSITION, position)
                .set(GRAPHITRON_ARGUMENT_PATH_SEGMENT.SEGMENT_NAME, segments[position])
                // Derived as capture derives it: the segments after the head up to this one, which
                // is the path a candidate under this pair's origin would be keyed by.
                .set(GRAPHITRON_ARGUMENT_PATH_SEGMENT.CANDIDATE_PATH,
                    String.join(".", java.util.Arrays.asList(segments)
                        .subList(1, position + 1)))
                .onDuplicateKeyIgnore()
                .execute();
        }
    }

    /**
     * A {@code @node} application on an object type: node identity, with the type id left unstated.
     * A case wanting the id states it with {@link #seedNodeWithTypeId}.
     */
    public static void seedNode(DSLContext dsl, String graphName, String typeName) {
        seedNodeWithTypeId(dsl, graphName, typeName, null);
    }

    /** {@link #seedNode} with the {@code typeId:} the author pinned. */
    public static void seedNodeWithTypeId(DSLContext dsl, String graphName, String typeName,
                                          String typeId) {
        seedDeclaredType(dsl, graphName, typeName, "OBJECT");
        dsl.insertInto(GRAPHITRON_NODE)
            .set(GRAPHITRON_NODE.GRAPH_NAME, graphName)
            .set(GRAPHITRON_NODE.TYPE_NAME, typeName)
            .set(GRAPHITRON_NODE.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_NODE.DECLARATION_LINE, SEED_LINE)
            .set(GRAPHITRON_NODE.DECLARATION_COLUMN, SEED_COLUMN)
            .set(GRAPHITRON_NODE.SOURCE_LINE, 2)
            .set(GRAPHITRON_NODE.SOURCE_COLUMN, 3)
            .set(GRAPHITRON_NODE.TYPE_ID, typeId)
            .execute();
    }


    /**
     * One ordered entry of an {@code @node(keyColumns:)} list, as written. It resolves against no
     * column: the pinned list is keyed by graph and type and needs no table to be stated, and a
     * name the bound table does not have is a state a detection names rather than one this relation
     * prevents.
     */
    public static void seedNodeKeyColumnRef(DSLContext dsl, String graphName, String typeName,
                                            int position, String columnRef) {
        dsl.insertInto(GRAPHITRON_NODE_KEY_COLUMN)
            .set(GRAPHITRON_NODE_KEY_COLUMN.GRAPH_NAME, graphName)
            .set(GRAPHITRON_NODE_KEY_COLUMN.TYPE_NAME, typeName)
            .set(GRAPHITRON_NODE_KEY_COLUMN.POSITION, position)
            .set(GRAPHITRON_NODE_KEY_COLUMN.COLUMN_REF, columnRef)
            .execute();
    }

    /**
     * A bare {@code @nodeId} application on a field or input field: presence with no
     * {@code typeName:}, which is the shape whose target the walk infers from a containing table
     * where there is one.
     */
    public static void seedNodeId(DSLContext dsl, String graphName, String typeName, String fieldName) {
        seedFieldNodeId(dsl, graphName, typeName, fieldName, null);
    }

    /**
     * {@link #seedNodeId} with the {@code typeName:} the author wrote. Null is the bare form, which
     * is a distinct fact rather than a missing value: at a position with no containing table it is
     * what a rejection reads.
     */
    public static void seedFieldNodeId(DSLContext dsl, String graphName, String typeName,
                                       String fieldName, String nodeTypeRef) {
        dsl.insertInto(GRAPHITRON_FIELD_NODE_ID)
            .set(GRAPHITRON_FIELD_NODE_ID.GRAPH_NAME, graphName)
            .set(GRAPHITRON_FIELD_NODE_ID.TYPE_NAME, typeName)
            .set(GRAPHITRON_FIELD_NODE_ID.FIELD_NAME, fieldName)
            .set(GRAPHITRON_FIELD_NODE_ID.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_FIELD_NODE_ID.SOURCE_LINE, 2)
            .set(GRAPHITRON_FIELD_NODE_ID.SOURCE_COLUMN, 3)
            .set(GRAPHITRON_FIELD_NODE_ID.NODE_TYPE_REF, nodeTypeRef)
            .execute();
    }

    /**
     * A {@code @nodeId} application on a field argument, the sibling relation of
     * {@link #seedFieldNodeId} keyed one grain further in. The argument is seeded if the case has
     * not, a directive application needing something to sit on.
     */
    public static void seedArgumentNodeId(DSLContext dsl, String graphName, String typeName,
                                          String fieldName, String argumentName,
                                          String nodeTypeRef) {
        if (!dsl.fetchExists(GRAPHQL_ARGUMENT, GRAPHQL_ARGUMENT.GRAPH_NAME.eq(graphName)
                .and(GRAPHQL_ARGUMENT.TYPE_NAME.eq(typeName))
                .and(GRAPHQL_ARGUMENT.FIELD_NAME.eq(fieldName))
                .and(GRAPHQL_ARGUMENT.ARGUMENT_NAME.eq(argumentName)))) {
            seedArgument(dsl, graphName, typeName, fieldName, argumentName, "ID");
        }
        dsl.insertInto(GRAPHITRON_ARGUMENT_NODE_ID)
            .set(GRAPHITRON_ARGUMENT_NODE_ID.GRAPH_NAME, graphName)
            .set(GRAPHITRON_ARGUMENT_NODE_ID.TYPE_NAME, typeName)
            .set(GRAPHITRON_ARGUMENT_NODE_ID.FIELD_NAME, fieldName)
            .set(GRAPHITRON_ARGUMENT_NODE_ID.ARGUMENT_NAME, argumentName)
            .set(GRAPHITRON_ARGUMENT_NODE_ID.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_ARGUMENT_NODE_ID.SOURCE_LINE, 2)
            .set(GRAPHITRON_ARGUMENT_NODE_ID.SOURCE_COLUMN, 3)
            .set(GRAPHITRON_ARGUMENT_NODE_ID.NODE_TYPE_REF, nodeTypeRef)
            .execute();
    }

    /**
     * A {@code @mutation} application on a field: the verb as the author wrote it. The write target
     * and the multi-row flag are left unstated, a relation reading them being a different subject
     * from one reading that the field carries the directive at all.
     */
    public static void seedMutation(DSLContext dsl, String graphName, String typeName,
                                    String fieldName, String operation) {
        dsl.insertInto(GRAPHITRON_MUTATION)
            .set(GRAPHITRON_MUTATION.GRAPH_NAME, graphName)
            .set(GRAPHITRON_MUTATION.TYPE_NAME, typeName)
            .set(GRAPHITRON_MUTATION.FIELD_NAME, fieldName)
            .set(GRAPHITRON_MUTATION.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_MUTATION.SOURCE_LINE, 2)
            .set(GRAPHITRON_MUTATION.SOURCE_COLUMN, 3)
            .set(GRAPHITRON_MUTATION.OPERATION, operation)
            .execute();
    }

    /**
     * The same with {@code table:} as the author spelled it, which is the write target's second
     * rung: a relation resolving one reads this only where the return type answers nothing.
     */
    public static void seedMutation(DSLContext dsl, String graphName, String typeName,
                                    String fieldName, String operation, String tableRef) {
        dsl.insertInto(GRAPHITRON_MUTATION)
            .set(GRAPHITRON_MUTATION.GRAPH_NAME, graphName)
            .set(GRAPHITRON_MUTATION.TYPE_NAME, typeName)
            .set(GRAPHITRON_MUTATION.FIELD_NAME, fieldName)
            .set(GRAPHITRON_MUTATION.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_MUTATION.SOURCE_LINE, 2)
            .set(GRAPHITRON_MUTATION.SOURCE_COLUMN, 3)
            .set(GRAPHITRON_MUTATION.OPERATION, operation)
            .set(GRAPHITRON_MUTATION.TABLE_REF, tableRef)
            .set(GRAPHITRON_MUTATION.TABLE_REF_NAMESPACE_PART, QualifiedNameGrammar.namespacePart(tableRef))
            .set(GRAPHITRON_MUTATION.TABLE_REF_NAME_PART, QualifiedNameGrammar.namePart(tableRef))
            .execute();
    }

    /** The first {@code @routine} application on a field, at the position the other helpers use. */
    public static void seedRoutine(DSLContext dsl, String graphName, String typeName,
                                   String fieldName, String routineRef) {
        seedRoutine(dsl, graphName, typeName, fieldName, 0, routineRef, 2);
    }

    /**
     * A {@code @routine} application at an ordinal the case names. The directive is repeatable and
     * several relations collapse the stack to one row, so which ordinal a row carries and where it
     * sits are what such a case asserts; both are arguments for that reason.
     *
     * @param ordinal the application's position in document order, as capture assigns it
     * @param sourceLine this application's own line, so a collapse can be read off the position
     */
    public static void seedRoutine(DSLContext dsl, String graphName, String typeName,
                                   String fieldName, int ordinal, String routineRef, int sourceLine) {
        dsl.insertInto(GRAPHITRON_ROUTINE)
            .set(GRAPHITRON_ROUTINE.GRAPH_NAME, graphName)
            .set(GRAPHITRON_ROUTINE.TYPE_NAME, typeName)
            .set(GRAPHITRON_ROUTINE.FIELD_NAME, fieldName)
            .set(GRAPHITRON_ROUTINE.ORDINAL, ordinal)
            .set(GRAPHITRON_ROUTINE.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_ROUTINE.SOURCE_LINE, sourceLine)
            .set(GRAPHITRON_ROUTINE.SOURCE_COLUMN, 3)
            .set(GRAPHITRON_ROUTINE.ROUTINE_REF, routineRef)
            .set(GRAPHITRON_ROUTINE.ROUTINE_REF_NAMESPACE_PART, QualifiedNameGrammar.namespacePart(routineRef))
            .set(GRAPHITRON_ROUTINE.ROUTINE_REF_NAME_PART, QualifiedNameGrammar.namePart(routineRef))
            .execute();
    }

    /**
     * A {@code @lookupKey} application on an argument: the live site, a marker and nothing else.
     * The argument is the case's to seed, its ordinal being what a relation picking one of several
     * marked arguments orders on.
     */
    public static void seedArgumentLookupKey(DSLContext dsl, String graphName, String typeName,
                                             String fieldName, String argumentName) {
        seedArgumentLookupKey(dsl, graphName, typeName, fieldName, argumentName, 2);
    }

    /**
     * The same marker at a line the case names. A relation picking one of a field's several marked
     * arguments answers with that argument's own position, so the position is how the pick is read.
     */
    public static void seedArgumentLookupKey(DSLContext dsl, String graphName, String typeName,
                                             String fieldName, String argumentName, int sourceLine) {
        dsl.insertInto(GRAPHITRON_ARGUMENT_LOOKUP_KEY)
            .set(GRAPHITRON_ARGUMENT_LOOKUP_KEY.GRAPH_NAME, graphName)
            .set(GRAPHITRON_ARGUMENT_LOOKUP_KEY.TYPE_NAME, typeName)
            .set(GRAPHITRON_ARGUMENT_LOOKUP_KEY.FIELD_NAME, fieldName)
            .set(GRAPHITRON_ARGUMENT_LOOKUP_KEY.ARGUMENT_NAME, argumentName)
            .set(GRAPHITRON_ARGUMENT_LOOKUP_KEY.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_ARGUMENT_LOOKUP_KEY.SOURCE_LINE, sourceLine)
            .set(GRAPHITRON_ARGUMENT_LOOKUP_KEY.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * The same marker on an input-object field, which is the retired site. A relation seeding a
     * closure from the input surface starts here, so a case about that closure states the marker on
     * the input field rather than on the argument that reaches it.
     */
    public static void seedInputFieldLookupKey(DSLContext dsl, String graphName, String typeName,
                                               String fieldName) {
        dsl.insertInto(GRAPHITRON_FIELD_LOOKUP_KEY)
            .set(GRAPHITRON_FIELD_LOOKUP_KEY.GRAPH_NAME, graphName)
            .set(GRAPHITRON_FIELD_LOOKUP_KEY.TYPE_NAME, typeName)
            .set(GRAPHITRON_FIELD_LOOKUP_KEY.FIELD_NAME, fieldName)
            .set(GRAPHITRON_FIELD_LOOKUP_KEY.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_FIELD_LOOKUP_KEY.SOURCE_LINE, 2)
            .set(GRAPHITRON_FIELD_LOOKUP_KEY.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * A connection marker on a field: that the author asked for the pagination expansion here. The
     * page size and the connection's chosen name are left unstated, a relation reading either being
     * a different subject from one reading that the promotion fires at all.
     */
    public static void seedConnection(DSLContext dsl, String graphName, String typeName,
                                      String fieldName) {
        dsl.insertInto(GRAPHITRON_CONNECTION)
            .set(GRAPHITRON_CONNECTION.GRAPH_NAME, graphName)
            .set(GRAPHITRON_CONNECTION.TYPE_NAME, typeName)
            .set(GRAPHITRON_CONNECTION.FIELD_NAME, fieldName)
            .set(GRAPHITRON_CONNECTION.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_CONNECTION.SOURCE_LINE, 2)
            .set(GRAPHITRON_CONNECTION.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * An {@code @orderBy} application on an argument: presence, which is the whole of what the
     * directive states at this coordinate. What the argument orders by lives on the input type it
     * names, so a case whose subject is the ordering itself seeds that too.
     */
    public static void seedOrderBy(DSLContext dsl, String graphName, String typeName,
                                   String fieldName, String argumentName) {
        dsl.insertInto(GRAPHITRON_ORDER_BY)
            .set(GRAPHITRON_ORDER_BY.GRAPH_NAME, graphName)
            .set(GRAPHITRON_ORDER_BY.TYPE_NAME, typeName)
            .set(GRAPHITRON_ORDER_BY.FIELD_NAME, fieldName)
            .set(GRAPHITRON_ORDER_BY.ARGUMENT_NAME, argumentName)
            .set(GRAPHITRON_ORDER_BY.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_ORDER_BY.SOURCE_LINE, 2)
            .set(GRAPHITRON_ORDER_BY.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * An {@code @asFacet} application on an input field: presence and a position, which is the whole
     * of what the directive states. What the facet binds is elsewhere, on the field's own row and on
     * its {@code @field} application, which is why a case about a facet seeds three rows and not
     * one.
     */
    public static void seedFacet(DSLContext dsl, String graphName, String typeName,
                                 String fieldName) {
        dsl.insertInto(GRAPHITRON_FACET)
            .set(GRAPHITRON_FACET.GRAPH_NAME, graphName)
            .set(GRAPHITRON_FACET.TYPE_NAME, typeName)
            .set(GRAPHITRON_FACET.FIELD_NAME, fieldName)
            .set(GRAPHITRON_FACET.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_FACET.SOURCE_LINE, 2)
            .set(GRAPHITRON_FACET.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * The raw application itself: that a directive of this name was written on a field, with none of
     * the decoding a semantic helper above stands for. The pair is what a fallback arm turns on, so
     * a case about one states the raw row alone and a case about the anti-join states both.
     */
    public static void seedFieldDirective(DSLContext dsl, String graphName, String typeName,
                                          String fieldName, String directiveName) {
        seedFieldDirective(dsl, graphName, typeName, fieldName, directiveName, 0, 2);
    }

    /** {@link #seedFieldDirective} at an ordinal and a line the case names, on {@link #seedRoutine}'s terms. */
    public static void seedFieldDirective(DSLContext dsl, String graphName, String typeName,
                                          String fieldName, String directiveName, int ordinal,
                                          int sourceLine) {
        dsl.insertInto(GRAPHQL_FIELD_DIRECTIVE)
            .set(GRAPHQL_FIELD_DIRECTIVE.GRAPH_NAME, graphName)
            .set(GRAPHQL_FIELD_DIRECTIVE.TYPE_NAME, typeName)
            .set(GRAPHQL_FIELD_DIRECTIVE.FIELD_NAME, fieldName)
            .set(GRAPHQL_FIELD_DIRECTIVE.DIRECTIVE_NAME, directiveName)
            .set(GRAPHQL_FIELD_DIRECTIVE.ORDINAL, ordinal)
            .set(GRAPHQL_FIELD_DIRECTIVE.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHQL_FIELD_DIRECTIVE.SOURCE_LINE, sourceLine)
            .set(GRAPHQL_FIELD_DIRECTIVE.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * {@link #seedFieldDirective} at the type grain, on the one declaration site this harness
     * spells. The type is seeded as an object if the case has not seeded it as something else,
     * {@link #seedDeclaredType} being idempotent.
     */
    public static void seedTypeDirective(DSLContext dsl, String graphName, String typeName,
                                         String directiveName) {
        seedTypeDirective(dsl, graphName, typeName, directiveName, 0, 1);
    }

    /**
     * {@link #seedTypeDirective} at an ordinal and a line the case names. Two applications of one
     * directive on one type is what a base declaration extended by a second site produces, and the
     * ordinal is the order a relation collapsing them reads.
     */
    public static void seedTypeDirective(DSLContext dsl, String graphName, String typeName,
                                         String directiveName, int ordinal, int sourceLine) {
        seedDeclaredType(dsl, graphName, typeName, "OBJECT");
        dsl.insertInto(GRAPHQL_TYPE_DIRECTIVE)
            .set(GRAPHQL_TYPE_DIRECTIVE.GRAPH_NAME, graphName)
            .set(GRAPHQL_TYPE_DIRECTIVE.TYPE_NAME, typeName)
            .set(GRAPHQL_TYPE_DIRECTIVE.DIRECTIVE_NAME, directiveName)
            .set(GRAPHQL_TYPE_DIRECTIVE.ORDINAL, ordinal)
            .set(GRAPHQL_TYPE_DIRECTIVE.DECLARATION_LINE, SEED_LINE)
            .set(GRAPHQL_TYPE_DIRECTIVE.DECLARATION_COLUMN, SEED_COLUMN)
            .set(GRAPHQL_TYPE_DIRECTIVE.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHQL_TYPE_DIRECTIVE.SOURCE_LINE, sourceLine)
            .set(GRAPHQL_TYPE_DIRECTIVE.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * A type's {@code implements} edge, in the declaration direction the relation stores. The
     * interface is a name the implementing type spelled and resolves against nothing, so a case can
     * state the edge without declaring the interface as a type.
     */
    public static void seedImplements(DSLContext dsl, String graphName, String typeName,
                                      String interfaceName) {
        seedDeclaredType(dsl, graphName, typeName, "OBJECT");
        // Numbered from one in the order the case states its implementors, which is what capture's
        // own pass produces for a schema whose implementors are written in that order. A fixture
        // has no second file to interleave, so seeding order is source order.
        int position = 1 + dsl.fetchCount(GRAPHQL_POLY_MEMBER,
            GRAPHQL_POLY_MEMBER.GRAPH_NAME.eq(graphName)
                .and(GRAPHQL_POLY_MEMBER.CONTAINER_NAME.eq(interfaceName))
                .and(GRAPHQL_POLY_MEMBER.CONTAINER_KIND.eq("INTERFACE")));
        dsl.insertInto(GRAPHQL_POLY_MEMBER)
            .set(GRAPHQL_POLY_MEMBER.GRAPH_NAME, graphName)
            .set(GRAPHQL_POLY_MEMBER.CONTAINER_KIND, "INTERFACE")
            .set(GRAPHQL_POLY_MEMBER.CONTAINER_NAME, interfaceName)
            .set(GRAPHQL_POLY_MEMBER.MEMBER_TYPE_NAME, typeName)
            .set(GRAPHQL_POLY_MEMBER.POSITION, position)
            .set(GRAPHQL_POLY_MEMBER.DECLARED_ON, typeName)
            .set(GRAPHQL_POLY_MEMBER.DECLARATION_LINE, SEED_LINE)
            .set(GRAPHQL_POLY_MEMBER.DECLARATION_COLUMN, SEED_COLUMN)
            .set(GRAPHQL_POLY_MEMBER.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHQL_POLY_MEMBER.SOURCE_LINE, 2)
            .set(GRAPHQL_POLY_MEMBER.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * A union's membership edge, in the declaration direction the relation stores: the union
     * declares the member, so the union is the declared type here where the implementing type is
     * the declared one on {@link #seedImplements}. The member's own ordinal is the union's authored
     * source order, so a case stating two members states which came first.
     */
    public static void seedUnionMember(DSLContext dsl, String graphName, String unionName,
                                       String memberTypeName, int ordinal) {
        seedDeclaredType(dsl, graphName, unionName, "UNION");
        dsl.insertInto(GRAPHQL_POLY_MEMBER)
            .set(GRAPHQL_POLY_MEMBER.GRAPH_NAME, graphName)
            .set(GRAPHQL_POLY_MEMBER.CONTAINER_KIND, "UNION")
            .set(GRAPHQL_POLY_MEMBER.CONTAINER_NAME, unionName)
            .set(GRAPHQL_POLY_MEMBER.MEMBER_TYPE_NAME, memberTypeName)
            .set(GRAPHQL_POLY_MEMBER.POSITION, ordinal)
            .set(GRAPHQL_POLY_MEMBER.DECLARED_ON, unionName)
            .set(GRAPHQL_POLY_MEMBER.DECLARATION_LINE, SEED_LINE)
            .set(GRAPHQL_POLY_MEMBER.DECLARATION_COLUMN, SEED_COLUMN)
            .set(GRAPHQL_POLY_MEMBER.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHQL_POLY_MEMBER.SOURCE_LINE, 2)
            .set(GRAPHQL_POLY_MEMBER.SOURCE_COLUMN, 3)
            .execute();
    }

    /**
     * A schema-level {@code @link}, decoded: the url as written, at an ordinal the case names.
     * Whether the url is the federation opt-in is a predicate a derivation applies, so this helper
     * takes whatever string the case wants tested against it, the omitted-argument state included
     * (a {@code null} url, which matches no prefix).
     */
    public static void seedLink(DSLContext dsl, String graphName, int ordinal, String url) {
        dsl.insertInto(GRAPHITRON_LINK)
            .set(GRAPHITRON_LINK.GRAPH_NAME, graphName)
            .set(GRAPHITRON_LINK.ORDINAL, ordinal)
            .set(GRAPHITRON_LINK.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_LINK.SOURCE_LINE, 1)
            .set(GRAPHITRON_LINK.SOURCE_COLUMN, 15)
            .set(GRAPHITRON_LINK.URL, url)
            .execute();
    }

    /**
     * An authored federation {@code @key}, decoded whole: the application row plus one selection per
     * path, each with its segments. Paths arrive as dotted strings for brevity ({@code "id"},
     * {@code "author.id"}) and are split into the segment rows the decode writes, so a case states
     * the field set the way an author writes it and the store holds it the way the grammar decoded
     * it. Positions are dense from zero in both children, as capture writes them.
     *
     * @param resolvable the {@code resolvable:} argument, or {@code null} where the author omitted it
     * @param paths      the field set's selections in written order; an empty array is the shape a
     *                   malformed {@code fields:} argument decodes to, which is a stated fact rather
     *                   than a gap
     */
    public static void seedFederationKey(DSLContext dsl, String graphName, String typeName,
                                         int ordinal, String fieldsSdl, Boolean resolvable,
                                         String... paths) {
        seedDeclaredType(dsl, graphName, typeName, "OBJECT");
        dsl.insertInto(GRAPHITRON_FEDERATION_KEY)
            .set(GRAPHITRON_FEDERATION_KEY.GRAPH_NAME, graphName)
            .set(GRAPHITRON_FEDERATION_KEY.TYPE_NAME, typeName)
            .set(GRAPHITRON_FEDERATION_KEY.ORDINAL, ordinal)
            .set(GRAPHITRON_FEDERATION_KEY.SOURCE_NAME, SEED_SOURCE)
            .set(GRAPHITRON_FEDERATION_KEY.DECLARATION_LINE, SEED_LINE)
            .set(GRAPHITRON_FEDERATION_KEY.DECLARATION_COLUMN, SEED_COLUMN)
            .set(GRAPHITRON_FEDERATION_KEY.SOURCE_LINE, 2)
            .set(GRAPHITRON_FEDERATION_KEY.SOURCE_COLUMN, 3)
            .set(GRAPHITRON_FEDERATION_KEY.FIELDS_SDL, fieldsSdl)
            .set(GRAPHITRON_FEDERATION_KEY.RESOLVABLE, resolvable)
            .execute();
        for (int position = 0; position < paths.length; position++) {
            dsl.insertInto(GRAPHITRON_FEDERATION_KEY_FIELD)
                .set(GRAPHITRON_FEDERATION_KEY_FIELD.GRAPH_NAME, graphName)
                .set(GRAPHITRON_FEDERATION_KEY_FIELD.TYPE_NAME, typeName)
                .set(GRAPHITRON_FEDERATION_KEY_FIELD.ORDINAL, ordinal)
                .set(GRAPHITRON_FEDERATION_KEY_FIELD.POSITION, position)
                .execute();
            String[] segments = paths[position].split("\\.");
            for (int segment = 0; segment < segments.length; segment++) {
                dsl.insertInto(GRAPHITRON_FEDERATION_KEY_FIELD_SEGMENT)
                    .set(GRAPHITRON_FEDERATION_KEY_FIELD_SEGMENT.GRAPH_NAME, graphName)
                    .set(GRAPHITRON_FEDERATION_KEY_FIELD_SEGMENT.TYPE_NAME, typeName)
                    .set(GRAPHITRON_FEDERATION_KEY_FIELD_SEGMENT.ORDINAL, ordinal)
                    .set(GRAPHITRON_FEDERATION_KEY_FIELD_SEGMENT.POSITION, position)
                    .set(GRAPHITRON_FEDERATION_KEY_FIELD_SEGMENT.SEGMENT_POSITION, segment)
                    .set(GRAPHITRON_FEDERATION_KEY_FIELD_SEGMENT.SEGMENT_NAME, segments[segment])
                    .execute();
            }
        }
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
     * A table whose generated model exposes no record class of its own. The catalog reports that as
     * {@code org.jooq.Record} rather than as an absence, so a rule reading the column has a name to
     * drop rather than a NULL to guard, and a rule that failed to drop it would hand every such
     * table the same class.
     */
    public static void seedTableWithoutRecordClass(DSLContext dsl, String sourceName,
                                                   String tableSchema, String tableName) {
        seedTable(dsl, sourceName, tableSchema, tableName);
        dsl.update(SQL_TABLE)
            .set(SQL_TABLE.RECORD_CLASS_FQN, "org.jooq.Record")
            .where(SQL_TABLE.SOURCE_NAME.eq(sourceName))
            .and(SQL_TABLE.TABLE_SCHEMA.eq(tableSchema))
            .and(SQL_TABLE.TABLE_NAME.eq(tableName))
            .execute();
    }

    /**
     * The callable behind a catalog object, with the generated call surface an emitted FROM clause
     * calls. Named for the catalog rather than for the directive, {@link #seedRoutine} being the
     * {@code @routine} application that names one of these. Both generated names are nullable
     * together in the census, so the overload below states the shape where the model exposes none.
     */
    public static void seedCatalogRoutine(DSLContext dsl, String sourceName, String tableSchema,
                                          String routineName, String routinesClassFqn,
                                          String methodName) {
        seedSchema(dsl, sourceName, tableSchema);
        dsl.insertInto(SQL_ROUTINE)
            .set(SQL_ROUTINE.SOURCE_NAME, sourceName)
            .set(SQL_ROUTINE.TABLE_SCHEMA, tableSchema)
            .set(SQL_ROUTINE.ROUTINE_NAME, routineName)
            .set(SQL_ROUTINE.ROUTINE_TYPE, "FUNCTION")
            .set(SQL_ROUTINE.ROUTINES_CLASS_FQN, routinesClassFqn)
            .set(SQL_ROUTINE.ROUTINES_METHOD_NAME, methodName)
            .execute();
    }

    /** The same row with no call surface, which is the two generated names null together. */
    public static void seedCatalogRoutine(DSLContext dsl, String sourceName, String tableSchema,
                                          String routineName) {
        seedCatalogRoutine(dsl, sourceName, tableSchema, routineName, null, null);
    }

    /** One IN parameter of a routine's call surface, at its 0-based position. */
    public static void seedRoutineParameter(DSLContext dsl, String sourceName, String tableSchema,
                                            String routineName, int position, String jooqName) {
        seedRoutineParameter(dsl, sourceName, tableSchema, routineName, position, jooqName,
            "java.lang.Integer");
    }

    /**
     * The same parameter with its Java type stated. A case comparing this type against something,
     * a projected key column's binding type being the one that does, states both sides rather than
     * relying on two defaults happening to agree.
     */
    public static void seedRoutineParameter(DSLContext dsl, String sourceName, String tableSchema,
                                            String routineName, int position, String jooqName,
                                            String bindingType) {
        dsl.insertInto(SQL_ROUTINE_PARAMETER)
            .set(SQL_ROUTINE_PARAMETER.SOURCE_NAME, sourceName)
            .set(SQL_ROUTINE_PARAMETER.TABLE_SCHEMA, tableSchema)
            .set(SQL_ROUTINE_PARAMETER.ROUTINE_NAME, routineName)
            .set(SQL_ROUTINE_PARAMETER.POSITION, position)
            .set(SQL_ROUTINE_PARAMETER.JOOQ_NAME, jooqName)
            .set(SQL_ROUTINE_PARAMETER.BINDING_TYPE, bindingType)
            .execute();
    }

    /**
     * A nullable text column. {@code jooqName} is stated rather than derived, the two-tier name
     * match being exactly what several relations decide on.
     */
    public static void seedColumn(DSLContext dsl, String sourceName, String tableSchema, String tableName,
                                  String columnName, int ordinal, String jooqName) {
        seedColumn(dsl, sourceName, tableSchema, tableName, columnName, ordinal, jooqName,
            "java.lang.String");
    }

    /**
     * The same column with the Java type jOOQ binds it to stated. A case whose subject is that type,
     * the key-column projection's type gate being the one that is, states it rather than reading the
     * default and having to know what it happens to be.
     */
    public static void seedColumn(DSLContext dsl, String sourceName, String tableSchema, String tableName,
                                  String columnName, int ordinal, String jooqName, String bindingType) {
        dsl.insertInto(SQL_COLUMN)
            .set(SQL_COLUMN.SOURCE_NAME, sourceName)
            .set(SQL_COLUMN.TABLE_SCHEMA, tableSchema)
            .set(SQL_COLUMN.TABLE_NAME, tableName)
            .set(SQL_COLUMN.COLUMN_NAME, columnName)
            .set(SQL_COLUMN.ORDINAL, ordinal)
            .set(SQL_COLUMN.JOOQ_NAME, jooqName)
            .set(SQL_COLUMN.SQL_TYPE, "character varying")
            .set(SQL_COLUMN.BINDING_TYPE, bindingType)
            .set(SQL_COLUMN.NULLABLE, true)
            .execute();
    }

    /**
     * An enum class a column of the source's generated model binds to, with the catalog type it
     * names. The grain is the class rather than the column, so a case about an enum several columns
     * bind states this once.
     *
     * @param tableSchema the enum type's SQL schema, {@code null} on a converter-bound Java enum
     *                    that names no catalog type at all
     * @param typeName the catalog enum type's name, {@code null} on the same terms
     */
    public static void seedEnumBinding(DSLContext dsl, String sourceName, String classFqn,
                                       String tableSchema, String typeName) {
        dsl.insertInto(SQL_ENUM_BINDING)
            .set(SQL_ENUM_BINDING.SOURCE_NAME, sourceName)
            .set(SQL_ENUM_BINDING.CLASS_FQN, classFqn)
            .set(SQL_ENUM_BINDING.TABLE_SCHEMA, tableSchema)
            .set(SQL_ENUM_BINDING.TYPE_NAME, typeName)
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
     * <p>{@code key_position} is assigned rather than taken, so a case does not have to state a
     * number it has no opinion about: a uniqueness constraint lands after the ones already seeded on
     * the table, which is the enumeration capture writes when it walks the generated model, and a
     * foreign key takes none because it is not in that enumeration. Seed the primary key before the
     * unique keys and the order matches what a real catalog produces.
     *
     * @param constraintType {@code PRIMARY KEY}, {@code UNIQUE} or {@code FOREIGN KEY}
     * @param jooqName the constant jOOQ generated for the key, or null where it generated none
     */
    public static void seedConstraint(DSLContext dsl, String sourceName, String tableSchema, String tableName,
                                      String constraintName, String constraintType, String jooqName) {
        Integer keyPosition = "FOREIGN KEY".equals(constraintType) ? null
            : dsl.fetchCount(SQL_CONSTRAINT,
                SQL_CONSTRAINT.SOURCE_NAME.eq(sourceName)
                    .and(SQL_CONSTRAINT.TABLE_SCHEMA.eq(tableSchema))
                    .and(SQL_CONSTRAINT.TABLE_NAME.eq(tableName))
                    .and(SQL_CONSTRAINT.KEY_POSITION.isNotNull()));
        dsl.insertInto(SQL_CONSTRAINT)
            .set(SQL_CONSTRAINT.KEY_POSITION, keyPosition)
            .set(SQL_CONSTRAINT.SOURCE_NAME, sourceName)
            .set(SQL_CONSTRAINT.TABLE_SCHEMA, tableSchema)
            .set(SQL_CONSTRAINT.TABLE_NAME, tableName)
            .set(SQL_CONSTRAINT.CONSTRAINT_NAME, constraintName)
            .set(SQL_CONSTRAINT.CONSTRAINT_TYPE, constraintType)
            .set(SQL_CONSTRAINT.JOOQ_NAME, jooqName)
            .execute();
    }

    /**
     * A table's primary key and the ordered columns under it, in one call: the constraint, the
     * table's claim on it, and one {@code sql_constraint_column} row per name in written order. The
     * columns must already exist, the constraint's own rows being anchored on them.
     */
    public static void seedPrimaryKey(DSLContext dsl, String sourceName, String tableSchema,
                                      String tableName, String constraintName, String... columnNames) {
        seedConstraint(dsl, sourceName, tableSchema, tableName, constraintName, "PRIMARY KEY", null);
        dsl.insertInto(SQL_PRIMARY_KEY)
            .set(SQL_PRIMARY_KEY.SOURCE_NAME, sourceName)
            .set(SQL_PRIMARY_KEY.TABLE_SCHEMA, tableSchema)
            .set(SQL_PRIMARY_KEY.TABLE_NAME, tableName)
            .set(SQL_PRIMARY_KEY.CONSTRAINT_NAME, constraintName)
            .execute();
        for (int position = 0; position < columnNames.length; position++) {
            dsl.insertInto(SQL_CONSTRAINT_COLUMN)
                .set(SQL_CONSTRAINT_COLUMN.SOURCE_NAME, sourceName)
                .set(SQL_CONSTRAINT_COLUMN.TABLE_SCHEMA, tableSchema)
                .set(SQL_CONSTRAINT_COLUMN.TABLE_NAME, tableName)
                .set(SQL_CONSTRAINT_COLUMN.CONSTRAINT_NAME, constraintName)
                .set(SQL_CONSTRAINT_COLUMN.POSITION, position)
                .set(SQL_CONSTRAINT_COLUMN.COLUMN_NAME, columnNames[position])
                .execute();
        }
    }

    /**
     * A unique constraint and the ordered columns under it. The sibling of {@link #seedPrimaryKey}
     * without the table's claim on it, which is the shape a case reaches for when what matters is
     * that a foreign key can point at something other than the primary key.
     */
    public static void seedUniqueKey(DSLContext dsl, String sourceName, String tableSchema,
                                     String tableName, String constraintName,
                                     String... columnNames) {
        seedConstraint(dsl, sourceName, tableSchema, tableName, constraintName, "UNIQUE", null);
        for (int position = 0; position < columnNames.length; position++) {
            dsl.insertInto(SQL_CONSTRAINT_COLUMN)
                .set(SQL_CONSTRAINT_COLUMN.SOURCE_NAME, sourceName)
                .set(SQL_CONSTRAINT_COLUMN.TABLE_SCHEMA, tableSchema)
                .set(SQL_CONSTRAINT_COLUMN.TABLE_NAME, tableName)
                .set(SQL_CONSTRAINT_COLUMN.CONSTRAINT_NAME, constraintName)
                .set(SQL_CONSTRAINT_COLUMN.POSITION, position)
                .set(SQL_CONSTRAINT_COLUMN.COLUMN_NAME, columnNames[position])
                .execute();
        }
    }

    /**
     * A foreign key and the ordered columns under it, in one call: the constraint, its own columns
     * in written order, and where it points. The referenced key must already exist, and the columns
     * must too, the constraint's rows being anchored on them.
     *
     * <p>Both ends live under one classpath entry, which is the ordinary catalog. A case whose
     * subject is a key spanning two entries states the three calls itself.
     *
     * <p>The column list is the key's own side. A reader pairing it with the referenced side does so
     * by position, which is what makes the order here the subject of any case about a lift.
     */
    public static void seedForeignKey(DSLContext dsl, String sourceName, String tableSchema,
                                      String tableName, String constraintName,
                                      String referencedTable, String referencedConstraintName,
                                      String... columnNames) {
        seedConstraint(dsl, sourceName, tableSchema, tableName, constraintName, "FOREIGN KEY", null);
        for (int position = 0; position < columnNames.length; position++) {
            dsl.insertInto(SQL_CONSTRAINT_COLUMN)
                .set(SQL_CONSTRAINT_COLUMN.SOURCE_NAME, sourceName)
                .set(SQL_CONSTRAINT_COLUMN.TABLE_SCHEMA, tableSchema)
                .set(SQL_CONSTRAINT_COLUMN.TABLE_NAME, tableName)
                .set(SQL_CONSTRAINT_COLUMN.CONSTRAINT_NAME, constraintName)
                .set(SQL_CONSTRAINT_COLUMN.POSITION, position)
                .set(SQL_CONSTRAINT_COLUMN.COLUMN_NAME, columnNames[position])
                .execute();
        }
        seedReferentialConstraint(dsl, sourceName, tableSchema, tableName, constraintName,
            sourceName, tableSchema, referencedTable, referencedConstraintName);
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
            dsl.insertInto(JVM_DECLARED_TYPE_REF)
                .set(JVM_DECLARED_TYPE_REF.SOURCE_NAME, sourceName)
                .set(JVM_DECLARED_TYPE_REF.CLASS_NAME, className)
                .set(JVM_DECLARED_TYPE_REF.OWNER_KIND, "METHOD_RETURN")
                .set(JVM_DECLARED_TYPE_REF.OWNER_POSITION, -1)
                .set(JVM_DECLARED_TYPE_REF.OWNER_NAME, methodName)
                .set(JVM_DECLARED_TYPE_REF.OWNER_DESCRIPTOR, descriptor)
                .set(JVM_DECLARED_TYPE_REF.TYPE_PATH, typePath)
                .set(JVM_DECLARED_TYPE_REF.REFERENCED_CLASS, referencedClass)
                .set(JVM_DECLARED_TYPE_REF.VARIANCE, "NONE")
                .execute());
    }

    /**
     * How a method's return type renders, for a case whose subject is what a reader displays rather
     * than which classes the type names. Both {@code seedMethod} overloads leave the two columns at
     * {@code Object}, which is coherent and says nothing; this states them, the method having been
     * seeded already.
     *
     * <p>Both forms are arguments because a classfile carries them separately: the erasure is what a
     * descriptor spells, and the declared form is what the source wrote, equal to the erasure
     * wherever erasure loses nothing. A helper deriving one from the other would be deciding a
     * compiler's question, and the pair is exactly what a case comparing the two is about.
     *
     * @param erased what {@code return_type} carries, the descriptor's own form with the package
     *        dropped ({@code List})
     * @param declared what {@code declared_return_type} carries, the source's form with the type
     *        arguments kept ({@code List<String>})
     */
    public static void seedReturnForm(DSLContext dsl, String sourceName, String className,
                                      String methodName, String descriptor,
                                      String erased, String declared) {
        dsl.update(JVM_METHOD)
            .set(JVM_METHOD.RETURN_TYPE, erased)
            .set(JVM_METHOD.DECLARED_RETURN_TYPE, declared)
            .where(JVM_METHOD.SOURCE_NAME.eq(sourceName))
            .and(JVM_METHOD.CLASS_NAME.eq(className))
            .and(JVM_METHOD.METHOD_NAME.eq(methodName))
            .and(JVM_METHOD.DESCRIPTOR.eq(descriptor))
            .execute();
    }

    /**
     * The same for a record component, on {@link #seedReturnForm}'s terms: {@code display_type} is
     * the erasure and {@code declared_type} the source's form.
     */
    public static void seedComponentForm(DSLContext dsl, String sourceName, String className,
                                         String componentName, String erased, String declared) {
        dsl.update(JVM_RECORD_COMPONENT)
            .set(JVM_RECORD_COMPONENT.DISPLAY_TYPE, erased)
            .set(JVM_RECORD_COMPONENT.DECLARED_TYPE, declared)
            .where(JVM_RECORD_COMPONENT.SOURCE_NAME.eq(sourceName))
            .and(JVM_RECORD_COMPONENT.CLASS_NAME.eq(className))
            .and(JVM_RECORD_COMPONENT.COMPONENT_NAME.eq(componentName))
            .execute();
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
        dsl.insertInto(JVM_DECLARED_TYPE_REF)
            .set(JVM_DECLARED_TYPE_REF.SOURCE_NAME, sourceName)
            .set(JVM_DECLARED_TYPE_REF.CLASS_NAME, className)
            .set(JVM_DECLARED_TYPE_REF.OWNER_KIND, "METHOD_RETURN")
                .set(JVM_DECLARED_TYPE_REF.OWNER_POSITION, -1)
                .set(JVM_DECLARED_TYPE_REF.OWNER_NAME, methodName)
            .set(JVM_DECLARED_TYPE_REF.OWNER_DESCRIPTOR, descriptor)
            .set(JVM_DECLARED_TYPE_REF.TYPE_PATH, typePath)
            .set(JVM_DECLARED_TYPE_REF.REFERENCED_CLASS, referencedClass)
            .set(JVM_DECLARED_TYPE_REF.VARIANCE, variance)
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
     * <p>The parameter seeded here is nameless, which is what a consumer compiled without
     * {@code -parameters} hands the census: the ordinal is its whole identity, and a rule reading
     * these rows may not depend on a name being there. The overload below names it.
     */
    public static void seedMethodParameter(DSLContext dsl, String sourceName, String className,
                                           String methodName, String descriptor, int position,
                                           Map<String, String> declaredType) {
        seedMethodParameter(dsl, sourceName, className, methodName, descriptor, position, null,
            declaredType);
    }

    /**
     * The same parameter carrying the name its classfile recorded, on the overload above's terms.
     * A rule that feeds a parameter from something an author wrote matches it by this name unless a
     * mapping redirects it, so a case about such a rule states the name and a case about its
     * absence takes the overload that leaves it NULL.
     */
    public static void seedMethodParameter(DSLContext dsl, String sourceName, String className,
                                           String methodName, String descriptor, int position,
                                           String parameterName, Map<String, String> declaredType) {
        dsl.insertInto(JVM_METHOD_PARAMETER)
            .set(JVM_METHOD_PARAMETER.SOURCE_NAME, sourceName)
            .set(JVM_METHOD_PARAMETER.CLASS_NAME, className)
            .set(JVM_METHOD_PARAMETER.METHOD_NAME, methodName)
            .set(JVM_METHOD_PARAMETER.DESCRIPTOR, descriptor)
            .set(JVM_METHOD_PARAMETER.POSITION, position)
            .set(JVM_METHOD_PARAMETER.PARAMETER_NAME, parameterName)
            .set(JVM_METHOD_PARAMETER.PARAMETER_TYPE, "Object")
            .set(JVM_METHOD_PARAMETER.DECLARED_PARAMETER_TYPE, "Object")
            .execute();
        declaredType.forEach((typePath, referencedClass) ->
            dsl.insertInto(JVM_DECLARED_TYPE_REF)
                .set(JVM_DECLARED_TYPE_REF.SOURCE_NAME, sourceName)
                .set(JVM_DECLARED_TYPE_REF.CLASS_NAME, className)
                .set(JVM_DECLARED_TYPE_REF.OWNER_KIND, "METHOD_PARAMETER")
                .set(JVM_DECLARED_TYPE_REF.OWNER_NAME, methodName)
                .set(JVM_DECLARED_TYPE_REF.OWNER_DESCRIPTOR, descriptor)
                .set(JVM_DECLARED_TYPE_REF.OWNER_POSITION, position)
                .set(JVM_DECLARED_TYPE_REF.TYPE_PATH, typePath)
                .set(JVM_DECLARED_TYPE_REF.REFERENCED_CLASS, referencedClass)
                .set(JVM_DECLARED_TYPE_REF.VARIANCE, "NONE")
                .execute());
    }

    /**
     * A whole condition-method signature in one line: the class, the method, and one parameter per
     * entry of {@code parameterClasses} typed by the class its declared type names at the root
     * position. What a case seeding a {@code @condition} reference wants, since the rule reading
     * these rows reads a method by name and then two parameter positions, so stating that shape
     * through {@link #seedClass}, {@link #seedMethod} and {@link #seedMethodParameter} takes four
     * calls whose only content is the same three names repeated.
     *
     * <p>A {@code null} entry is a parameter whose declared type names no class at all, which is
     * what a primitive one is; a wildcard {@code Table<?>} is spelled as the bare interface
     * {@code org.jooq.Table}, because that is the class the census records at the root of it and
     * the unbounded wildcard below it names nothing. The two absences are different facts and the
     * relations reading them tell them apart, so this helper does not fold them together.
     *
     * <p>The descriptor is derived from the parameter classes, so two overloads of one name are two
     * rows as long as their signatures differ, which is the only property a case about overloads
     * needs from it. The class row is seeded idempotently, two overloads of one class being the
     * ordinary case here.
     */
    public static void seedConditionMethod(DSLContext dsl, String sourceName, String className,
                                           String methodName, String... parameterClasses) {
        dsl.insertInto(JVM_CLASS)
            .set(JVM_CLASS.SOURCE_NAME, sourceName)
            .set(JVM_CLASS.CLASS_NAME, className)
            .set(JVM_CLASS.CLASS_KIND, "CLASS")
            .onDuplicateKeyIgnore()
            .execute();
        var descriptor = new StringBuilder("(");
        for (String parameterClass : parameterClasses) {
            descriptor.append(parameterClass == null ? "I"
                : "L" + parameterClass.replace('.', '/') + ";");
        }
        descriptor.append(")Lorg/jooq/Condition;");
        seedMethod(dsl, sourceName, className, methodName, descriptor.toString());
        dsl.update(JVM_METHOD)
            .set(JVM_METHOD.RETURNS_CONDITION, true)
            .where(JVM_METHOD.SOURCE_NAME.eq(sourceName))
            .and(JVM_METHOD.CLASS_NAME.eq(className))
            .and(JVM_METHOD.METHOD_NAME.eq(methodName))
            .and(JVM_METHOD.DESCRIPTOR.eq(descriptor.toString()))
            .execute();
        for (int position = 0; position < parameterClasses.length; position++) {
            String parameterClass = parameterClasses[position];
            seedMethodParameter(dsl, sourceName, className, methodName, descriptor.toString(),
                position, parameterClass == null ? Map.of() : Map.of("", parameterClass));
        }
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
            dsl.insertInto(JVM_DECLARED_TYPE_REF)
                .set(JVM_DECLARED_TYPE_REF.SOURCE_NAME, sourceName)
                .set(JVM_DECLARED_TYPE_REF.CLASS_NAME, className)
                .set(JVM_DECLARED_TYPE_REF.OWNER_KIND, "RECORD_COMPONENT")
                .set(JVM_DECLARED_TYPE_REF.OWNER_DESCRIPTOR, "")
                .set(JVM_DECLARED_TYPE_REF.OWNER_POSITION, -1)
                .set(JVM_DECLARED_TYPE_REF.OWNER_NAME, componentName)
                .set(JVM_DECLARED_TYPE_REF.TYPE_PATH, typePath)
                .set(JVM_DECLARED_TYPE_REF.REFERENCED_CLASS, referencedClass)
                .set(JVM_DECLARED_TYPE_REF.VARIANCE, "NONE")
                .execute());
    }

    // ===== The derivations' own tables =====

    /**
     * One member of the classification domain. A materialization rather than a view, because the
     * closure it holds is over a type graph that has cycles, so a relation gated on domain
     * membership reads rows some writer put there and a case about that gate states them.
     *
     * <p>Which types the writer would have reached is a different question with a different home:
     * seeding a member the seeds could not have reached, or leaving out one they would have, is how
     * a case tells a gate on this relation apart from a gate on anything the members happen to
     * carry.
     */
    public static void seedTypeDomain(DSLContext dsl, String graphName, String typeName) {
        dsl.insertInto(INTENT_TYPE_DOMAIN)
            .set(INTENT_TYPE_DOMAIN.GRAPH_NAME, graphName)
            .set(INTENT_TYPE_DOMAIN.TYPE_NAME, typeName)
            .execute();
    }

    /**
     * A type backed by a class, on {@link #seedTypeDomain}'s terms: another closure over a cyclic
     * type graph, so it is a materialization and a relation reading it reads rows a writer put
     * there. The type is seeded as an object unless the case already gave it a kind, an input
     * object being backed here too and the difference mattering to a reader that guards on kind.
     *
     * <p>Which class a producer would actually have delivered is not this helper's question. A row
     * naming a class no census declares is the ordinary case, since what backs a type is a name the
     * closure carried and not an entry anything has to hold.
     */
    public static void seedTypeBackingClass(DSLContext dsl, String graphName, String typeName,
                                            String className) {
        seedType(dsl, graphName, typeName, "OBJECT");
        dsl.insertInto(INTENT_TYPE_BACKING_CLASS)
            .set(INTENT_TYPE_BACKING_CLASS.GRAPH_NAME, graphName)
            .set(INTENT_TYPE_BACKING_CLASS.TYPE_NAME, typeName)
            .set(INTENT_TYPE_BACKING_CLASS.CLASS_NAME, className)
            .execute();
    }
}
