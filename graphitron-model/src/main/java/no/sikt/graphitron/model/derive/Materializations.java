package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.Name;
import org.jooq.exception.DataAccessException;

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
 *
 * <p>Both cadences owe the planner statistics on what they just wrote, which is {@link #analyse},
 * and the two reach it differently for a reason stated there rather than here: a refresh may run
 * inside a transaction and an analysis may not.
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
     *
     * <p>Analyses the targets it refilled, which it may do inline where {@link #refresh} may not:
     * this path holds no transaction of its own, and its readers are exactly the surfaces a person
     * waits on, so the statistics {@link #analyse} supplies are worth more here than anywhere.
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
        analyse(dsl);
    }

    /**
     * Gathers statistics on every registered target, so the planner uses the indexes declared
     * beside them. Idempotent, and cheap enough not to need a cadence argument of its own: one
     * statement per registered target, over tables of the size a fact store holds.
     *
     * <p>Needed at all because H2 gathers none on its own here. Its automatic analysis fires after
     * a table has taken more changes than {@code ANALYZE_AUTO} allows, which is two thousand by
     * default, and a target refilled from a schema of real size takes a few hundred; so absent
     * this call the planner reads every target as having the row count and selectivity it assumes
     * for a table it has never looked at. The difference is most of the gain the indexes exist
     * for. On the read-cost gate's twelve-unit fixture the deepest reader over the reference-step
     * hop table costs 8880 scans with the index and no statistics, and 523 with both.
     *
     * <p><b>Must not run inside a transaction, which is why this is a call of its own rather than
     * a step of {@link #refresh}.</b> H2 commits the current transaction as a side effect of
     * {@code ANALYZE}, verified by inserting a row, analysing, and rolling back: the row survives.
     * Capture's refresh runs inside capture's transaction precisely so that no reader observes an
     * emptied target, and an implicit commit between the delete and the rest of the capture would
     * publish exactly that state. So the capture path analyses after its transaction closes, from
     * the caller that owns the transaction, and {@link #refreshAll} analyses inline, holding none.
     *
     * <p>Scoped to the registered targets rather than the whole database, for a measured reason
     * and not only a modest one. A bare {@code ANALYZE} also restates statistics for the hundred
     * and forty-five captured tables, which are keyed and which nothing here just rewrote, and on
     * the same fixture it left one reader dearer than the targeted form did. The materializer
     * states statistics for what the materializer wrote.
     *
     * <p>Best-effort, and that is this store's standing posture rather than a special case for this
     * call: the fact store is a cache shared by every module of a workspace, and
     * {@code FactCapture}'s fallback to a private in-memory store says outright that warmth is the
     * only thing a cache is ever allowed to cost. Statistics are an optimisation on top of that
     * warmth. A registered target is a table another writer may hold at the moment this runs, and
     * failing a build to state a selectivity would be the wrong trade by an order of magnitude, so a
     * database refusal here leaves the planner on whatever it had. Only a database refusal: anything
     * that is not a {@link DataAccessException} is a programming error and propagates.
     *
     * <p>Returning the count rather than logging the refusals is what keeps best-effort from
     * meaning unobserved. A swallowed exception is only safe while the thing being swallowed is
     * rare and incidental, and the failure that would break that is a malformed statement, which
     * would refuse on every target of every store and show up as nothing at all. The count makes
     * that case assertable, and {@code MaterializeRegistryGateTest} asserts it: on a healthy store
     * every registration is analysed. This module carries jOOQ and H2 and no logging framework, so
     * a return value is also the only signal available without adding one for a line nobody reads.
     *
     * @return how many of the registered targets were analysed, which is all of them on a store
     *     nothing else is holding
     */
    public static int analyse(DSLContext dsl) {
        int analysed = 0;
        for (Registration registration : registrations(dsl)) {
            try {
                dsl.execute("ANALYZE TABLE "
                    + dsl.render(table(relation(registration.targetTableName()))));
                analysed++;
            } catch (DataAccessException refused) {
                // Left uncounted rather than rethrown, per the paragraph above; the caller's own
                // reads go on planning against whatever statistics the target already carried.
            }
        }
        return analysed;
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
