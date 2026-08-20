package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Name;

import java.util.List;
import java.util.Locale;

import static org.jooq.impl.DSL.asterisk;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.select;
import static org.jooq.impl.DSL.table;

/**
 * The materializer: refills every registered target table from the view that states its rule.
 *
 * <p>A registration exists because a derivation is a view H2 evaluates correctly and far too
 * often. H2 inlines a view wherever it is named and eliminates no common subexpression, so a
 * relation a deep derivation names dozens of times is evaluated dozens of times per read. The
 * registry's answer is to keep the rule in a view and move the canonical name every reader
 * already spells onto a table of the same shape, which this class refills. No consumer is edited
 * and no answer changes: a target holds exactly the rows its view computes, because that is the
 * statement that fills it. The registry rows and the doctrine that admits them live in
 * {@code meta_materialize}; the contributor-facing rationale is in
 * {@code docs/architecture/explanation/fact-model.adoc}.
 *
 * <p>Two refresh shapes, chosen per target by whether it carries a {@code graph_name} column
 * rather than by anything the registry states. A graph-keyed target is emptied and refilled for
 * one graph at a time, because a capture of one graph has no business rewriting a sibling's rows;
 * a target with no graph in its shape is refreshed whole. Both are plain {@code DELETE} rather
 * than {@code TRUNCATE}: H2 refuses to truncate a table any foreign key references, and
 * {@code DELETE FROM t CASCADE} silently parses {@code CASCADE} as a table alias and cascades
 * nothing.
 *
 * <p>Deliberately plain-name jOOQ rather than the generated table constants, for two reasons that
 * both hold on their own. The relation names are data read out of the registry, so no compile-time
 * constant could name them; and this module's hand-written half does not reference its own
 * generated half, the same rule {@code no.sikt.graphitron.model.catalog.StoreCatalog} states.
 * Registry rows spell relation names as the DDL declares them, in lower case, while H2's catalog
 * holds the folded upper-case spelling, so every name is folded here before it becomes an
 * identifier.
 *
 * <p>Callers refresh on one of two cadences and the difference is a real contract, not a
 * convenience. Capture calls {@link #refresh} inside its own transaction once the run's rows are
 * flushed, so a target is current exactly when the partition it derives from is. A reader that
 * opens a store without capturing into it (the language server, the MCP server, a warm start that
 * skipped capture because nothing changed) calls {@link #refreshAll}, which assumes nothing about
 * whether a capture ran.
 */
public final class Materializations {

    /** One {@code meta_materialize} row: the view stating a rule, and the table holding its rows. */
    public record Registration(String sourceViewName, String targetTableName) {}

    private Materializations() {}

    /**
     * Refills every registered target for one graph. Graph-keyed targets are refreshed for
     * {@code graphName} alone; a target with no graph in its shape is refreshed whole, there being
     * no partition of it to scope to.
     *
     * <p>Runs on the caller's {@link DSLContext}, which is how capture keeps the refresh inside its
     * own transaction: no reader ever observes an emptied target.
     */
    public static void refresh(DSLContext dsl, String graphName) {
        for (Registration registration : registrations(dsl)) {
            refresh(dsl, registration, graphName);
        }
    }

    /**
     * Refills every registered target for every graph the store holds, and every graph-free target
     * whole. The entry point for a reader that opens a store it did not capture into: it is correct
     * whether or not a capture ever ran, and idempotent, so calling it on open costs one evaluation
     * of each registered view and cannot leave a target stale.
     */
    public static void refreshAll(DSLContext dsl) {
        List<Registration> registrations = registrations(dsl);
        if (registrations.isEmpty()) {
            return;
        }
        List<String> graphs = dsl.select(field(name("GRAPH_NAME"), String.class))
            .from(table(name("STORE_GRAPH")))
            .orderBy(field(name("GRAPH_NAME")))
            .fetch(0, String.class);
        for (Registration registration : registrations) {
            if (graphKeyed(dsl, registration.targetTableName())) {
                for (String graph : graphs) {
                    refreshPartition(dsl, registration, graph);
                }
            } else {
                refreshWhole(dsl, registration);
            }
        }
    }

    /** The registry's rows, in a fixed order so a refresh is deterministic. */
    public static List<Registration> registrations(DSLContext dsl) {
        return dsl.select(field(name("SOURCE_VIEW_NAME"), String.class),
                field(name("TARGET_TABLE_NAME"), String.class))
            .from(table(name("META_MATERIALIZE")))
            .orderBy(field(name("SOURCE_VIEW_NAME")))
            .fetch(r -> new Registration(r.value1(), r.value2()));
    }

    private static void refresh(DSLContext dsl, Registration registration, String graphName) {
        if (graphKeyed(dsl, registration.targetTableName())) {
            refreshPartition(dsl, registration, graphName);
        } else {
            refreshWhole(dsl, registration);
        }
    }

    private static void refreshPartition(DSLContext dsl, Registration registration, String graphName) {
        var graph = field(name("GRAPH_NAME"), String.class);
        dsl.deleteFrom(table(relation(registration.targetTableName())))
            .where(graph.eq(graphName))
            .execute();
        dsl.insertInto(table(relation(registration.targetTableName())))
            .select(select(asterisk())
                .from(table(relation(registration.sourceViewName())))
                .where(graph.eq(graphName)))
            .execute();
    }

    private static void refreshWhole(DSLContext dsl, Registration registration) {
        dsl.deleteFrom(table(relation(registration.targetTableName()))).execute();
        dsl.insertInto(table(relation(registration.targetTableName())))
            .select(select(asterisk()).from(table(relation(registration.sourceViewName()))))
            .execute();
    }

    /** Whether the relation carries a {@code graph_name} column, which decides the refresh shape. */
    private static boolean graphKeyed(DSLContext dsl, String relationName) {
        return dsl.fetchExists(
            select(field(name("COLUMN_NAME")))
                .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
                .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .and(field(name("TABLE_NAME"), String.class).eq(fold(relationName)))
                .and(field(name("COLUMN_NAME"), String.class).eq("GRAPH_NAME")));
    }

    private static Name relation(String relationName) {
        return name(fold(relationName));
    }

    private static String fold(String relationName) {
        return relationName.toUpperCase(Locale.ROOT);
    }
}
