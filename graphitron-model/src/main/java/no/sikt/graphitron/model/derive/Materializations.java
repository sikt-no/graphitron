package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Name;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

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
 * <p>Registrations refresh in the derived dependency order: a registration whose source view
 * reads another registration's target, directly or through unregistered intermediate views,
 * refreshes after it. The edges live in {@code meta_materialize_dependency}, written once per
 * booted store by {@link MaterializeDependencies}, and {@link #refreshOrder} computes the
 * sequence from the rows without re-parsing anything. Ordering composes with both refresh shapes
 * without new machinery: within one pass a prerequisite is always fully refreshed in the scope
 * being refreshed before a dependent reads it, and rows outside the current scope are current
 * already, each capture having refreshed its own partition inside its own transaction. That
 * covers the two mixed pairings too: a partitioned dependent of a whole prerequisite sees the
 * prerequisite refreshed whole first, and a whole dependent of a partitioned prerequisite sees
 * the current partition fresh and the sibling partitions current from their own captures.
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

    /**
     * The registrations in the one sequence a refresh may execute them: every prerequisite ahead
     * of every registration whose view reads its target, alphabetical among the unordered. Its own
     * type rather than a second {@code List} because the census reader's order is a convenience
     * where this one is a correctness property, and one list cannot carry both contracts without
     * the difference living only in javadoc.
     */
    public record RefreshOrder(List<Registration> registrations) {
        public RefreshOrder {
            registrations = List.copyOf(registrations);
        }
    }

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
        for (Registration registration : refreshOrder(dsl).registrations()) {
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
        List<Registration> registrations = refreshOrder(dsl).registrations();
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

    /**
     * The registry's rows, alphabetically by source view: the census reader, for callers that
     * enumerate registrations without refreshing anything. A refresh executes
     * {@link #refreshOrder} instead, whose sequence is a correctness property where this order is
     * a stable convenience.
     */
    public static List<Registration> registrations(DSLContext dsl) {
        return dsl.select(field(name("SOURCE_VIEW_NAME"), String.class),
                field(name("TARGET_TABLE_NAME"), String.class))
            .from(table(name("META_MATERIALIZE")))
            .orderBy(field(name("SOURCE_VIEW_NAME")))
            .fetch(r -> new Registration(r.value1(), r.value2()));
    }

    /**
     * The refresh sequence: Kahn's algorithm over {@code meta_materialize_dependency}'s rows with
     * an alphabetical tie-break on the registration key, so a row-free relation yields exactly
     * {@link #registrations}' order and the refresh stays deterministic.
     *
     * <p>Refusing a cycle here is defense in depth rather than the invariant's home: every store
     * boots the same DDL the build-time gate ran against, so a cycle that reaches a refresh is a
     * gate that never ran, not a schema an author can meet.
     *
     * @throws IllegalStateException if the dependency rows contain a cycle, naming it; no refresh
     *         order can make every target in a cycle equal its view on a settled store, each
     *         needing another's target current first
     */
    public static RefreshOrder refreshOrder(DSLContext dsl) {
        List<Registration> census = registrations(dsl);
        var byName = new LinkedHashMap<String, Registration>();
        census.forEach(r -> byName.put(r.sourceViewName(), r));
        var unmet = new LinkedHashMap<String, TreeSet<String>>();
        census.forEach(r -> unmet.put(r.sourceViewName(), new TreeSet<>()));
        dsl.select(field(name("SOURCE_VIEW_NAME"), String.class),
                field(name("DEPENDS_ON"), String.class))
            .from(table(name("META_MATERIALIZE_DEPENDENCY")))
            .fetch()
            .forEach(row -> unmet.get(row.value1()).add(row.value2()));

        var ordered = new ArrayList<Registration>(census.size());
        while (!unmet.isEmpty()) {
            // Entries keep the census's alphabetical order, so the first satisfied one is the
            // alphabetically least among those whose prerequisites are all placed: the tie-break.
            String next = unmet.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow(() -> cycle(unmet));
            unmet.remove(next);
            unmet.values().forEach(prerequisites -> prerequisites.remove(next));
            ordered.add(byName.get(next));
        }
        return new RefreshOrder(ordered);
    }

    /**
     * Names a cycle out of the stuck state: every remaining registration waits on another
     * remaining one, so following any first unmet prerequisite must revisit a registration, and
     * the path from that revisit is a cycle.
     */
    private static IllegalStateException cycle(Map<String, TreeSet<String>> unmet) {
        var path = new ArrayList<String>();
        String current = unmet.keySet().iterator().next();
        while (!path.contains(current)) {
            path.add(current);
            current = unmet.get(current).first();
        }
        var loop = path.subList(path.indexOf(current), path.size());
        return new IllegalStateException(
            "the materialization registry's derived dependencies contain a cycle: "
                + String.join(" -> ", loop) + " -> " + current
                + ". No refresh order can make every target in it equal its view on a settled"
                + " store, each registration needing another's target current first, so the"
                + " registrations themselves must change.");
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
