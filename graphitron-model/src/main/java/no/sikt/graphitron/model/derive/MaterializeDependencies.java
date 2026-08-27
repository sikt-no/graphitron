package no.sikt.graphitron.model.derive;

import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * The one writer of {@code meta_materialize_dependency}: derives the materialization registry's
 * refresh edges from the store's own stored view definitions and rewrites the relation with them.
 *
 * <p>For each registration in {@code meta_materialize}, the walk takes the stored definition of
 * its source view from {@code INFORMATION_SCHEMA.VIEWS}, parses it with jOOQ's SQL parser, and
 * collects the relations it reads from the query object model rather than from text. A read of an
 * unregistered view recurses into that view's definition, a read of a registered target becomes a
 * row saying the target's registration refreshes first, and base tables end the walk. An AST walk
 * rather than a textual scan because it has no false positives to disclaim, and a view definition
 * the parser refuses is a loud failure at population rather than a silently missing edge.
 *
 * <p>The edges are a function of the DDL alone, which sets the cadence: the store's bootstrap
 * calls {@link #populate} once per created store, before the first refresh, and refreshes and
 * gates read the rows without ever re-parsing. The rewrite is idempotent and inserts in a fixed
 * order, so two runs over one store write byte-identical rows and the relation is deterministic
 * run to run.
 *
 * <p>The walk answers a second question the rows do not carry, which
 * {@link #registrationsReachedByView} exposes: not the order refreshes must run in, but which
 * registrations are in a given view's subtree at all. That is the reach a cost claim ranges over,
 * a registration being able to change only what its own readers evaluate, and it is the same walk
 * rather than a second one so the two answers cannot come to disagree about what reads what.
 *
 * <p>The parse, and the normalization rules it leans on, belong to {@link ViewReferences}, which
 * this walk reads through rather than re-deriving. What an edge needs is the set of relations a
 * definition reads; that walk keeps each reference's multiplicity and position as well, which a
 * refresh order has no use for, a relation read twice ordering exactly as one read once. Plain-name
 * jOOQ throughout, for {@link Materializations}' own reasons: the relation names are data read out
 * of the registry and the catalog, and this module's hand-written half does not reference its own
 * generated half.
 */
public final class MaterializeDependencies {

    /** One derived edge: the dependent registration, and the one whose target it reads. */
    private record Edge(String sourceViewName, String dependsOn) implements Comparable<Edge> {
        @Override
        public int compareTo(Edge other) {
            int bySource = sourceViewName.compareTo(other.sourceViewName);
            return bySource != 0 ? bySource : dependsOn.compareTo(other.dependsOn);
        }
    }

    private MaterializeDependencies() {}

    /**
     * Rewrites {@code meta_materialize_dependency} from the current registry and the stored view
     * definitions: every row deleted, the derived edges inserted in their fixed order, all inside
     * one transaction so no reader meets the relation half-written.
     *
     * @throws IllegalStateException if a walked view's stored definition does not parse, or if a
     *         registered source view reads its own target, both of which are defects in the DDL
     *         rather than anything a run caused
     */
    public static void populate(DSLContext dsl) {
        List<Materializations.Registration> registrations = Materializations.registrations(dsl);
        Map<String, String> kinds = relationKinds(dsl);
        Map<String, String> registrationOfTarget = new HashMap<>();
        registrations.forEach(r -> registrationOfTarget.put(r.targetTableName(), r.sourceViewName()));

        Set<Edge> edges = new TreeSet<>();
        Map<String, Set<String>> reads = new HashMap<>();
        for (Materializations.Registration registration : registrations) {
            var reached = registrationsReachedFrom(dsl, registration.sourceViewName(), kinds,
                registrationOfTarget, reads);
            for (Map.Entry<String, String> hit : reached.entrySet()) {
                String prerequisite = hit.getKey();
                if (prerequisite.equals(registration.sourceViewName())) {
                    String through = hit.getValue();
                    throw new IllegalStateException("the source view "
                        + registration.sourceViewName() + " reads its own target "
                        + registration.targetTableName()
                        + (through.equals(registration.sourceViewName().toLowerCase(Locale.ROOT))
                            ? "" : " (through " + through + ")")
                        + ", so no refresh could make the target equal the view;"
                        + " the registration itself must change");
                }
                edges.add(new Edge(registration.sourceViewName(), prerequisite));
            }
        }

        dsl.transaction(tx -> {
            DSLContext txDsl = tx.dsl();
            txDsl.deleteFrom(table(name("META_MATERIALIZE_DEPENDENCY"))).execute();
            for (Edge edge : edges) {
                txDsl.insertInto(table(name("META_MATERIALIZE_DEPENDENCY")),
                        field(name("SOURCE_VIEW_NAME"), String.class),
                        field(name("DEPENDS_ON"), String.class))
                    .values(edge.sourceViewName(), edge.dependsOn())
                    .execute();
            }
        });
    }

    /**
     * For every view in the store, the registrations whose target its derivation reaches: the pairs
     * where materializing one relation can change what evaluating another costs.
     *
     * <p>The same walk {@link #populate} runs, asked the other way round. There it starts at each
     * registered source view and the answer is a refresh order; here it starts at every view and the
     * answer is which registrations are in that view's subtree at all. A registration can only
     * change what a relation costs if the relation's derivation names its target, so a cost claim
     * over the pairs this returns is a claim over every pair that could hold and no pair that
     * could not.
     *
     * <p>Both axes come off the booted store rather than a copy of the DDL, which is the rule the
     * dependency rows are already built on: a view added to the schema puts its own cells in the
     * domain with nothing to keep in step by hand.
     *
     * <p>A walk stops at a registered target, so a target's own subtree is absent from the answer.
     * That is the cost question stated correctly rather than a simplification: a reader meeting a
     * registered target reads a table, so what fills that table is not evaluated during the read
     * and the registrations beneath it cost that reader nothing.
     *
     * @return each view, lowercased, mapped to the source-view names of the registrations it
     *         reaches; a view reaching none maps to an empty set
     */
    public static Map<String, Set<String>> registrationsReachedByView(DSLContext dsl) {
        Map<String, String> kinds = relationKinds(dsl);
        Map<String, String> registrationOfTarget = new HashMap<>();
        Materializations.registrations(dsl)
            .forEach(r -> registrationOfTarget.put(r.targetTableName(), r.sourceViewName()));
        Map<String, Set<String>> reads = new HashMap<>();
        Map<String, Set<String>> reached = new TreeMap<>();
        kinds.forEach((relation, kind) -> {
            if ("VIEW".equals(kind)) {
                reached.put(relation, new TreeSet<>(registrationsReachedFrom(
                    dsl, relation, kinds, registrationOfTarget, reads).keySet()));
            }
        });
        return reached;
    }

    /**
     * The registrations whose target a walk from {@code start} meets, each mapped to the view in the
     * walk that read it, which is what lets a caller say where a reach came from. Reads of
     * unregistered views recurse; reads of registered targets and of base tables end that branch.
     *
     * <p>{@code reads} memoizes {@link ViewReferences#relationsReadBy} across calls. A view's
     * stored definition is
     * a function of the catalog alone, so one parse per view serves every walk that reaches it, and
     * a caller walking from many starts pays one parse per view rather than one per visit.
     */
    private static Map<String, String> registrationsReachedFrom(
            DSLContext dsl, String start, Map<String, String> kinds,
            Map<String, String> registrationOfTarget, Map<String, Set<String>> reads) {
        Map<String, String> reached = new TreeMap<>();
        var walked = new HashSet<String>();
        var frontier = new ArrayDeque<String>();
        frontier.add(start.toLowerCase(Locale.ROOT));
        while (!frontier.isEmpty()) {
            String view = frontier.poll();
            if (!walked.add(view)) {
                continue;
            }
            for (String read : reads.computeIfAbsent(view, v -> ViewReferences.relationsReadBy(dsl, v))) {
                String registration = registrationOfTarget.get(read);
                if (registration != null) {
                    reached.putIfAbsent(registration, view);
                } else if ("VIEW".equals(kinds.get(read))) {
                    frontier.add(read);
                }
            }
        }
        return reached;
    }

    /** Every relation in the store's schema, lowercased, mapped to the engine's kind for it. */
    private static Map<String, String> relationKinds(DSLContext dsl) {
        Map<String, String> kinds = new HashMap<>();
        dsl.select(field(name("TABLE_NAME"), String.class), field(name("TABLE_TYPE"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "TABLES")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .fetch()
            .forEach(row -> kinds.put(row.value1().toLowerCase(Locale.ROOT), row.value2()));
        return kinds;
    }
}
