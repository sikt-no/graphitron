package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.derive.ArgMappingCandidates;
import no.sikt.graphitron.model.derive.ArgumentReferenceStepTargets;
import no.sikt.graphitron.model.derive.ArgumentScopeTables;
import no.sikt.graphitron.model.derive.NodeIdDecodeColumns;
import no.sikt.graphitron.model.derive.NodeIdDecodeHopColumns;
import no.sikt.graphitron.model.derive.NodeIdDecodeHops;
import no.sikt.graphitron.model.derive.NodeIdInstructions;
import no.sikt.graphitron.model.capture.document.EmittedAnchor;
import no.sikt.graphitron.model.derive.NameMatchedKeys;
import no.sikt.graphitron.model.derive.Nodes;
import no.sikt.graphitron.model.derive.NodeKeyColumns;
import no.sikt.graphitron.model.derive.NodeLegacyKeyShapes;
import no.sikt.graphitron.model.derive.TableTypes;
import no.sikt.graphitron.model.derive.CarrierDataFields;
import no.sikt.graphitron.model.derive.FieldScopeTables;
import no.sikt.graphitron.model.derive.FieldReferenceStepHops;
import no.sikt.graphitron.model.derive.InputFieldReferenceStepTargets;
import no.sikt.graphitron.model.derive.InputFieldResolvingTables;
import no.sikt.graphitron.model.derive.FieldReferenceStepTargets;
import no.sikt.graphitron.model.derive.ResolvedTypeBindings;
import no.sikt.graphitron.model.derive.SpelledTables;
import org.jooq.DSLContext;

import java.time.LocalDateTime;
import java.util.function.Consumer;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

/**
 * Rows, stated directly: one named helper per anchor row, over a store {@link FactStores} opened.
 * The harness for a test whose subject is what a relation this module declares returns given rows,
 * which is a view's joins, its outer edges, or a check constraint's boundary.
 *
 * <p><b>Dissolving.</b> Seeding was how a case reached derived rows while this module could not
 * run the gatherers: it stated the rows a gatherer would have written. It can run them now, over
 * the sakila fixtures and the SDL corpus, so a case states its facts as a document under
 * {@code src/test/resources/facts} and capture produces the rows. Seeded rows test the fixture, so
 * do not add a case here and convert one you touch;
 * {@link no.sikt.graphitron.model.SeedingDissolutionGateTest} holds the count down.
 *
 * <p>Nothing remains. A case that wants a state no capture reaches is not a case this file is kept
 * for: a pattern nobody writes on purpose is a property test's subject, and a shape nobody cares
 * about is not a subject at all. There is no partial conversion either, so a class that still names
 * this one has not converted.
 *
 * <p>Until a case converts it should still say which state it stands in for, so the next reader
 * can tell what its conversion has to reach. Javadoc habit, nothing this harness enforces.
 *
 * <p>Helpers are stateless and take the {@link DSLContext} first, so a case static-imports the ones
 * it uses and reads as a list of rows. Ordering follows the foreign keys: the graph and the source
 * before the membership joining them. The source and membership helpers are idempotent, since
 * several graphs routinely read one source; the graph helper inserts once and lets a duplicate fail
 * loudly rather than passing silently.
 *
 * <p>Expect this set to shrink. A helper whose last caller converts goes with it, which is the
 * whole reason the helpers are in one file: what is left is countable.
 */
public final class SeededStore {

    /**
     * The instant a seeded reading stamps its anchor rows with. One value for every fixture,
     * because a fixture seeds one reading: the sweep tells readings apart by this, and two calls
     * inside one fixture are the same reading rather than two.
     */
    private static final java.time.LocalDateTime SEED_INSTANT =
        java.time.LocalDateTime.of(2000, 1, 1, 0, 0);

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
     * Store one derivation and the missing stratum becomes visible at once: a table holds rows
     * only once something fills it. This is that stratum, running the stages production runs by the
     * calls production makes, so the two cannot drift apart in what a stage computes; a test never
     * names a stage, and a rule becoming one costs this fixture one line.
     *
     * <p>Every graph the store holds rather than one the caller names, which is the shape a seeded
     * store wants: a case seeds its graphs and then reads, several of them read across graphs to
     * assert partition isolation, and picking per helper would be a judgement each one could get
     * wrong for no gain. Idempotent, and on a store of a dozen rows the cost does not register, so
     * a helper calls it unconditionally rather than reasoning about whether an earlier one already
     * did.
     */
    public static void derive(DSLContext dsl) {
        // The catalog's own closure first, and outside the per-graph loop because it is not a
        // graph's fact: whether a function result can be keyed to a table is a question about a
        // catalog. By the production call, so a fixture cannot hold its own idea of which name
        // matches are whole. It has to be here at all because the relation is derived where the
        // view it replaced computed on read, and a harness that seeds the catalog by hand gets
        // nothing from a derivation nobody ran.
        NameMatchedKeys.derive(dsl);
        // The element anchors first, because every relation keyed at an emitted coordinate points
        // at them and the supertype transcription below is the first of those. By the production
        // call, so a fixture cannot hold its own idea of what the emitted population is.
        for (var graph : dsl.select(STORE_GRAPH.GRAPH_NAME).from(STORE_GRAPH)
                .fetch(STORE_GRAPH.GRAPH_NAME)) {
            EmittedAnchor.derive(dsl, graph, SEED_INSTANT);
        }
        transcribeSupertypes(dsl);
        // The candidate tree, by the same call capture makes. Stated here rather than seeded row by
        // row because the rule is pure SQL over rows a case has already seeded, so running it is
        // both cheaper than a per-case seeder and one fewer place for a fixture to hold its own
        // idea of the descent.
        for (var graph : dsl.select(STORE_GRAPH.GRAPH_NAME).from(STORE_GRAPH)
                .fetch(STORE_GRAPH.GRAPH_NAME)) {
            TableTypes.derive(dsl, graph);
            Nodes.derive(dsl, graph);
            NodeKeyColumns.derive(dsl, graph);
            NodeLegacyKeyShapes.derive(dsl, graph);
            ArgMappingCandidates.derive(dsl, graph);
            // The reference stratum's own resolutions, in the order capture runs them: the
            // spelling first, then the two hop arms that read it. Here for the reason the name
            // matches above are here, that the rows are a stage's now where they used to be a
            // rule computed on read, and a harness that seeds the catalog by hand gets nothing
            // from a stage nobody ran.
            SpelledTables.derive(dsl, graph);
            FieldReferenceStepHops.deriveKeyed(dsl, graph);
            FieldReferenceStepHops.deriveKeyless(dsl, graph);
            ResolvedTypeBindings.derive(dsl, graph);
            FieldReferenceStepTargets.derive(dsl, graph);
            // The derivation stratum's stages, after the gatherer's own above because that is
            // where each one's rule reads from, in the order DerivationStratum runs them. The
            // hand-written producers are not among them: a seeded case writes their tables itself.
            CarrierDataFields.derive(dsl, graph);
            FieldScopeTables.derive(dsl, graph);
            ArgumentScopeTables.derive(dsl, graph);
            InputFieldResolvingTables.derive(dsl, graph);
            ArgumentReferenceStepTargets.derive(dsl, graph);
            InputFieldReferenceStepTargets.derive(dsl, graph);
            NodeIdInstructions.derive(dsl, graph);
            NodeIdDecodeHops.derive(dsl, graph);
            NodeIdDecodeHopColumns.derive(dsl, graph);
            NodeIdDecodeColumns.derive(dsl, graph);
        }
    }

    /**
     * Fills the three relations capture writes beside a site rather than instead of it, from the site
     * rows a case seeded. Stands in for capture's second write, and lives here rather than in the
     * seed helpers for a reason about when it can run: capture knows a spelling is authored at the
     * moment it reads the directive, and a fixture only knows which sites exist once the case has
     * finished seeding, so the transcription has to be late. {@link #derive} is the line every case
     * already calls at exactly that moment.
     *
     * <p>Beside rather than instead of is the whole of what selects a relation into this method.
     *
     * <p>The method reference is the mixed case and shows both halves at once. Nine of its sites
     * keep relations of their own and are transcribed from them below; the tenth, the source row,
     * carried nothing beyond the shared fact, so it has no relation to transcribe from and a case
     * meaning that site writes {@code graphitron_method_reference_entry} itself.
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
        dsl.execute("DELETE FROM graphitron_spelled_reference_entry");
        dsl.execute("""
            INSERT INTO graphitron_spelled_reference_entry (graph_name, spelling, namespace_part, name_part)
            SELECT graph_name, spelling, namespace_part, name_part FROM (
              SELECT graph_name, COALESCE(table_ref, type_name) AS spelling,
                     table_ref_namespace_part AS namespace_part,
                     COALESCE(table_ref_name_part, type_name) AS name_part
                FROM graphitron_table_entry
              UNION
              SELECT graph_name, table_ref, table_ref_namespace_part, table_ref_name_part
                FROM graphitron_field_reference_step_entry WHERE table_ref IS NOT NULL
              UNION
              SELECT graph_name, table_ref, table_ref_namespace_part, table_ref_name_part
                FROM graphitron_argument_reference_step_entry WHERE table_ref IS NOT NULL
              UNION
              SELECT graph_name, table_ref, table_ref_namespace_part, table_ref_name_part
                FROM graphitron_mutation_entry WHERE table_ref IS NOT NULL
              UNION
              SELECT graph_name, routine_ref, routine_ref_namespace_part, routine_ref_name_part
                FROM graphitron_routine_entry) spellings
            """);
        dsl.execute("DELETE FROM graphitron_field_target");
        dsl.execute("""
            INSERT INTO graphitron_field_target
              (graph_name, type_name, field_name, target_type_name, wrapper_type_name, touched_at)
            SELECT graph_name, type_name, field_name, target_type_name, wrapper_type_name,
                   CURRENT_TIMESTAMP
              FROM graphitron_field_target_rule
            """);
        dsl.execute("DELETE FROM graphitron_method_reference_entry");
        dsl.execute("""
            INSERT INTO graphitron_method_reference_entry
              (graph_name, site, use_site, type_name, field_name, argument_name, ordinal,
               step_position, class_name, method, source_name, source_line, source_column)
            SELECT graph_name, 'SERVICE', type_name || '.' || field_name,
                   type_name, field_name, NULL, NULL, NULL,
                   class_name, method, source_name, source_line, source_column
              FROM graphitron_service_entry
             WHERE class_name IS NOT NULL AND method IS NOT NULL
            UNION ALL
            SELECT graph_name, 'EXTERNAL_FIELD', type_name || '.' || field_name,
                   type_name, field_name, NULL, NULL, NULL,
                   class_name, method, source_name, source_line, source_column
              FROM graphitron_external_field_entry
             WHERE class_name IS NOT NULL AND method IS NOT NULL
            UNION ALL
            SELECT s.graph_name, 'FIELD_REFERENCE_STEP',
                   s.type_name || '.' || s.field_name || '#' || CAST(s.ordinal AS VARCHAR)
                     || '[' || CAST(s.position AS VARCHAR) || ']',
                   s.type_name, s.field_name, NULL, s.ordinal, s.position,
                   s.class_name, s.method, d.source_name, d.source_line, d.source_column
              FROM graphitron_field_reference_step_entry s
              JOIN graphitron_field_reference_entry d
                ON d.graph_name = s.graph_name AND d.type_name = s.type_name
               AND d.field_name = s.field_name AND d.ordinal = s.ordinal
             WHERE s.class_name IS NOT NULL AND s.method IS NOT NULL
            UNION ALL
            SELECT s.graph_name, 'ARGUMENT_REFERENCE_STEP',
                   s.type_name || '.' || s.field_name || '(' || s.argument_name || ')#'
                     || CAST(s.ordinal AS VARCHAR) || '[' || CAST(s.position AS VARCHAR) || ']',
                   s.type_name, s.field_name, s.argument_name, s.ordinal, s.position,
                   s.class_name, s.method, d.source_name, d.source_line, d.source_column
              FROM graphitron_argument_reference_step_entry s
              JOIN graphitron_argument_reference_entry d
                ON d.graph_name = s.graph_name AND d.type_name = s.type_name
               AND d.field_name = s.field_name AND d.argument_name = s.argument_name
               AND d.ordinal = s.ordinal
             WHERE s.class_name IS NOT NULL AND s.method IS NOT NULL
            """);
    }

    // ===== Anchors =====

    /**
     * The reading a planted row belongs to.
     *
     * <p>Every anchor a mark and sweep governs carries the instant of the reading that derived it,
     * so a fixture planting one has to say which reading that was. A constant rather than the clock:
     * a fixture's rows are all one reading's, and a test that captures over them wants its own
     * instant to differ from this one rather than to race it.
     */
    public static final LocalDateTime SEEDED_READING = LocalDateTime.of(2000, 1, 1, 0, 0);

    /**
     * How many stores the thread funnel under this harness has booted in this JVM, one per thread
     * that ran a body or a borrow through it. For a module pinning its boot count from outside this
     * package; see {@link #threadStoreBootingThreads()}.
     */
    public static long threadStoreBoots() {
        return ThreadConfinedStore.boots();
    }

    /**
     * How many distinct threads have booted a funnel store. Equal to {@link #threadStoreBoots()}
     * exactly while no thread has booted twice, which is the funnel's confinement claim.
     */
    public static int threadStoreBootingThreads() {
        return ThreadConfinedStore.bootingThreads();
    }

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
}
