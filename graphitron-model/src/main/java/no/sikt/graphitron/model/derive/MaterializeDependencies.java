package no.sikt.graphitron.model.derive;

import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Name;
import org.jooq.Query;
import org.jooq.Table;
import org.jooq.VisitListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <p>Collection leans on a property of H2's stored definitions: every real relation reference is
 * normalized to its schema-qualified spelling ({@code "PUBLIC"."NAME"}), while aliases, common
 * table expression names and other transient table-like parts stay unqualified. Filtering on the
 * qualified shape is what keeps an alias that happens to share a relation's name from minting an
 * edge. Plain-name jOOQ throughout, for {@link Materializations}' own reasons: the relation names
 * are data read out of the registry and the catalog, and this module's hand-written half does not
 * reference its own generated half.
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
        for (Materializations.Registration registration : registrations) {
            var walked = new HashSet<String>();
            var frontier = new ArrayDeque<String>();
            frontier.add(registration.sourceViewName().toLowerCase(Locale.ROOT));
            while (!frontier.isEmpty()) {
                String view = frontier.poll();
                if (!walked.add(view)) {
                    continue;
                }
                for (String read : relationsReadBy(dsl, view)) {
                    String prerequisite = registrationOfTarget.get(read);
                    if (prerequisite != null) {
                        if (prerequisite.equals(registration.sourceViewName())) {
                            throw new IllegalStateException("the source view "
                                + registration.sourceViewName() + " reads its own target " + read
                                + (view.equals(registration.sourceViewName().toLowerCase(Locale.ROOT))
                                    ? "" : " (through " + view + ")")
                                + ", so no refresh could make the target equal the view;"
                                + " the registration itself must change");
                        }
                        edges.add(new Edge(registration.sourceViewName(), prerequisite));
                    } else if ("VIEW".equals(kinds.get(read))) {
                        frontier.add(read);
                    }
                }
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
     * The relations the named view's stored definition reads directly, lowercased: parsed, then
     * collected by rendering the parsed query under a listener and keeping every table part whose
     * qualified name is exactly {@code PUBLIC} plus one segment, the shape H2's normalization
     * gives real relation references and nothing else.
     */
    private static Set<String> relationsReadBy(DSLContext dsl, String viewName) {
        String definition = dsl.select(field(name("VIEW_DEFINITION"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "VIEWS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).eq(viewName.toUpperCase(Locale.ROOT)))
            .fetchOne(0, String.class);
        if (definition == null) {
            throw new IllegalStateException("the catalog holds no stored definition for view "
                + viewName + ", which the dependency walk reached from a registered source view");
        }
        Query query;
        try {
            query = dsl.parser().parseQuery(definition);
        } catch (RuntimeException e) {
            throw new IllegalStateException("the stored definition of view " + viewName
                + " did not parse, and the materialization refresh order is derived from it: "
                + e.getMessage(), e);
        }
        Set<String> read = new TreeSet<>();
        VisitListener collector = VisitListener.onVisitStart(context -> {
            if (context.queryPart() instanceof Table<?> part) {
                Name qualified = part.getQualifiedName();
                if (qualified.parts().length == 2 && "PUBLIC".equals(qualified.first())) {
                    read.add(qualified.last().toLowerCase(Locale.ROOT));
                }
            }
        });
        Configuration rendering = new DefaultConfiguration().set(dsl.dialect()).set(collector);
        DSL.using(rendering).render(query);
        return read;
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
