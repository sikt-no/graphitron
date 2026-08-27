package no.sikt.graphitron.model.derive;

import org.jooq.CommonTableExpression;
import org.jooq.Configuration;
import org.jooq.DSLContext;
import org.jooq.Name;
import org.jooq.Query;
import org.jooq.QueryPart;
import org.jooq.Select;
import org.jooq.Table;
import org.jooq.TableField;
import org.jooq.VisitListener;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultConfiguration;
import org.jooq.impl.QOM;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * What a stored view definition reads, and <em>where</em> each reference sits: one entry per
 * reference rather than per relation, each carrying the positions that decide how many times the
 * engine runs the referenced rule's body during one read of this view.
 *
 * <p>The distinction this class exists for is that naming a rule and evaluating it are different
 * quantities. H2 inlines a view wherever it is named and eliminates no common subexpression, so a
 * rule named twice is expanded twice; but a rule named <em>once</em> inside a correlated subquery
 * is evaluated once per driving row, and one named inside a recursive term is expanded afresh per
 * iteration. Counting references alone models the first mechanism and scores the other two as one,
 * which is why {@link #readBy} keeps the position and the multiplicity that a set of relation names
 * discards.
 *
 * <p>{@link MaterializeDependencies} is the other reader of this walk and wants only the relation
 * names, its question being which registration refreshes before which. Both go through here so the
 * normalization rules are stated once: a real relation reference is the one H2 spells
 * schema-qualified ({@code "PUBLIC"."NAME"}), while aliases and common table expression names stay
 * unqualified, and filtering on that shape is what keeps an alias sharing a relation's name from
 * counting as a read of it.
 *
 * <p><strong>What the three positions are worth is not equal, and a caller weighting them should
 * know which is which.</strong> {@link Position#RECURSIVE} and {@link Position#CORRELATED} are read
 * off semantics that survive planning: a self-referencing common table expression is evaluated per
 * iteration whatever the planner does with it, and a subquery naming a relation bound outside
 * itself cannot be hoisted out of its driving row. {@link Position#INNER_SIDE} is weaker, and
 * deliberately named apart rather than folded in with them. It is read off the join order in the
 * stored definition, and H2 normalizes an inner join's predicate out of {@code ON} and into
 * {@code WHERE}, leaving {@code ON 1=1} behind and the planner free to drive from either side. So
 * an inner-side reading is the shape as written and the shape as executed only where the join order
 * is fixed, which is the outer joins. Treat it as a suspect worth pricing rather than as a
 * measurement, the same standing the register's own reasons give a scan count.
 */
public final class ViewReferences {

    /**
     * Where a reference sits, in terms of what re-evaluates the rule it names. Ordered weakest
     * first, so {@link Reference#position()} can report the strongest of an enclosing chain by
     * taking the maximum.
     */
    public enum Position {

        /** On the non-driving side of a join, as the stored definition spells the join order. */
        INNER_SIDE,

        /**
         * Inside a subquery that names a relation bound outside itself, so the engine evaluates it
         * once per row of the query it is correlated to.
         */
        CORRELATED,

        /**
         * Inside a common table expression whose own body names it, so the term is evaluated once
         * per iteration of the walk. The anchor counts too: H2 evaluates it beside the recursive
         * term rather than once.
         */
        RECURSIVE
    }

    /**
     * One structure that re-evaluates what it encloses, and the relations it re-evaluates them
     * against: the driving side of a join, the query level a subquery borrows a name from, or the
     * terms a recursive walk accumulates over.
     *
     * <p>The drivers are here because a position on its own says a rule runs repeatedly and not how
     * repeatedly, and the difference between a probe against six rows and one against nine hundred
     * is the whole question. They name relations rather than carrying a count: what a relation
     * holds is a property of the store, which a caller reads when it wants to weight, and what
     * drives what is a property of the definition, which is this walk's to state.
     *
     * <p>Empty drivers mean the walk could not name the driving side, not that there is none. A
     * caller weighting by cardinality should treat that as unknown rather than as one.
     */
    public record Enclosure(Position position, Set<String> drivers) {

        public Enclosure {
            drivers = Set.copyOf(drivers);
        }
    }

    /**
     * One reference to one relation, with the chain of re-evaluating structures enclosing it,
     * outermost first. An unenclosed reference carries an empty chain and is evaluated once per
     * naming.
     *
     * <p>The chain rather than a single label because the structures nest and their multipliers
     * multiply: a view on the inner side of a join inside a recursive term is evaluated once per
     * iteration <em>times</em> once per driving row, and a caller that kept only the strongest
     * would have to re-derive the rest. {@link #position()} is the single-label reading for a
     * caller that wants one.
     */
    public record Reference(String relation, List<Enclosure> enclosing) {

        public Reference {
            enclosing = List.copyOf(enclosing);
        }

        /** The positions enclosing this reference, outermost first. */
        public List<Position> positions() {
            return enclosing.stream().map(Enclosure::position).toList();
        }

        /** The strongest position enclosing this reference, or empty when nothing re-evaluates it. */
        public Optional<Position> position() {
            return positions().stream().max(Comparator.naturalOrder());
        }

        /** Whether anything re-evaluates this reference beyond the once its naming already costs. */
        public boolean reEvaluated() {
            return !enclosing.isEmpty();
        }
    }

    /**
     * A visited relation reference with its whole path kept, because what drives it is read off the
     * paths of the <em>other</em> references: the driving side of a join is whichever relations sit
     * under the join's first operand, which is a question about visits this one knows nothing about.
     */
    private record Visit(String name, boolean namesARelation, List<QueryPart> path) {

        /** The relation this visit names, for a visit that names one. */
        String relation() {
            return name;
        }

        List<QueryPart> levels() {
            return path.stream().filter(part -> part instanceof Select<?>).toList();
        }

        List<String> ctes() {
            return ctesOf(path);
        }

        /** Whether {@code ancestor} is on this visit's path, followed there by {@code child}. */
        boolean descendsThrough(QueryPart ancestor, QueryPart child) {
            for (int i = 0; i < path.size() - 1; i++) {
                if (path.get(i) == ancestor && path.get(i + 1) == child) {
                    return true;
                }
            }
            return false;
        }
    }

    private ViewReferences() {}

    /**
     * Every reference the named view's stored definition makes to a relation in the store's schema,
     * in visit order, one entry per reference: a relation named three times yields three entries.
     *
     * @throws IllegalStateException if the catalog holds no definition for the view, or if the
     *         stored definition does not parse, both being defects in the DDL rather than anything
     *         a run caused
     */
    public static List<Reference> readBy(DSLContext dsl, String viewName) {
        Query query = parse(dsl, viewName, definitionOf(dsl, viewName));

        List<Visit> visits = new ArrayList<>();
        List<Visit> terms = new ArrayList<>();
        Map<QueryPart, Set<String>> boundAt = new IdentityHashMap<>();
        Map<QueryPart, Set<String>> qualifiersAt = new IdentityHashMap<>();
        Set<String> recursiveCtes = new HashSet<>();

        VisitListener collector = VisitListener.onVisitStart(context -> {
            QueryPart part = context.queryPart();
            List<QueryPart> ancestors = ancestorsOf(context.queryParts());
            if (part instanceof Table<?> visited && !qualifiesAField(ancestors)) {
                visitTable(visited, ancestors, visits, terms, boundAt, recursiveCtes);
            } else if (part instanceof TableField<?, ?> visited) {
                Table<?> qualifier = visited.getTable();
                if (qualifier != null) {
                    innermostLevel(ancestors).ifPresent(level -> qualifiersAt
                        .computeIfAbsent(level, l -> new TreeSet<>())
                        .add(normalize(qualifier.getUnqualifiedName())));
                }
            }
        });
        Configuration rendering = new DefaultConfiguration().set(dsl.dialect()).set(collector);
        DSL.using(rendering).render(query);

        Set<QueryPart> correlated = correlatedLevels(boundAt, qualifiersAt);
        List<Reference> references = new ArrayList<>();
        for (Visit visit : visits) {
            references.add(new Reference(visit.relation(),
                enclosuresOf(visit, visits, terms, correlated, recursiveCtes)));
        }
        return List.copyOf(references);
    }

    /**
     * The distinct relations the named view reads, which is {@link #readBy} with multiplicity and
     * position discarded: the question a refresh order asks, where reading a relation twice and
     * reading it once impose the same ordering.
     */
    public static Set<String> relationsReadBy(DSLContext dsl, String viewName) {
        Set<String> relations = new TreeSet<>();
        readBy(dsl, viewName).forEach(reference -> relations.add(reference.relation()));
        return relations;
    }

    /**
     * The parts surrounding a visited one, outermost first. jOOQ hands the visit its own path with
     * itself on the end, and every question here is about the surroundings: a common table
     * expression is its own ancestor in that path, which would make every one of them look
     * self-referencing and so recursive.
     */
    private static List<QueryPart> ancestorsOf(QueryPart[] path) {
        return List.of(path).subList(0, Math.max(0, path.length - 1));
    }

    /**
     * Whether a visited table is a column's qualifier rather than a relation the query reads.
     * jOOQ visits the table of a qualified field as a table part in its own right, so {@code o.a}
     * puts {@code o} in the walk beside the {@code FROM} entry that introduced it. Counting those
     * would bind an outer alias inside the subquery that borrows it, which is exactly the evidence
     * correlation is read from, and would report a relation named once in a predicate as named
     * twice.
     */
    private static boolean qualifiesAField(List<QueryPart> ancestors) {
        return ancestors.stream().anyMatch(part -> part instanceof TableField<?, ?>);
    }

    /**
     * Records one visited table part: a schema-qualified name is a relation reference and becomes a
     * {@link Visit}, while an unqualified one is an alias or a common table expression name, which
     * binds into its query level and can mark an enclosing expression recursive by naming it.
     */
    private static void visitTable(Table<?> visited, List<QueryPart> ancestors,
            List<Visit> visits, List<Visit> terms,
            Map<QueryPart, Set<String>> boundAt, Set<String> recursiveCtes) {
        if (visited instanceof QOM.JoinTable<?, ?>) {
            return;
        }
        Name qualified = visited.getQualifiedName();
        String unqualified = normalize(visited.getUnqualifiedName());
        innermostLevel(ancestors).ifPresent(level ->
            boundAt.computeIfAbsent(level, l -> new TreeSet<>()).add(unqualified));

        if (qualified.parts().length == 2 && "PUBLIC".equals(qualified.first())) {
            visits.add(new Visit(qualified.last().toLowerCase(Locale.ROOT), true, ancestors));
            return;
        }
        if (qualified.parts().length == 1) {
            terms.add(new Visit(unqualified, false, ancestors));
            if (ctesOf(ancestors).contains(unqualified)) {
                recursiveCtes.add(unqualified);
            }
        }
    }

    /**
     * The structures enclosing one visited reference, outermost first, each with the relations it
     * re-evaluates that reference against.
     */
    private static List<Enclosure> enclosuresOf(Visit visit, List<Visit> visits, List<Visit> terms,
            Set<QueryPart> correlated, Set<String> recursiveCtes) {
        List<Enclosure> enclosing = new ArrayList<>();
        for (String cte : visit.ctes()) {
            if (recursiveCtes.contains(cte)) {
                enclosing.add(new Enclosure(Position.RECURSIVE, accumulatedOver(cte, visits)));
            }
        }
        List<QueryPart> levels = visit.levels();
        for (int i = 0; i < levels.size(); i++) {
            if (correlated.contains(levels.get(i))) {
                enclosing.add(new Enclosure(Position.CORRELATED, i == 0
                    ? Set.of()
                    : resolved(boundDirectlyAt(levels.get(i - 1), visits),
                        boundDirectlyAt(levels.get(i - 1), terms), visits)));
            }
        }
        for (QOM.JoinTable<?, ?> join : innerSideJoins(visit)) {
            enclosing.add(new Enclosure(Position.INNER_SIDE,
                resolved(drivingSideOf(join, visits), drivingSideOf(join, terms), visits)));
        }
        return enclosing;
    }

    /**
     * The joins that hold a reference on their non-driving side. A join in the model carries its two
     * operands as {@code $table1} and {@code $table2}, so the question is answered by identity
     * against the operand the path descended into, rather than by reading the rendered shape: a
     * reference under {@code $table2} is re-evaluated against the rows {@code $table1} produces,
     * however deeply the derived tables between them nest.
     */
    private static List<QOM.JoinTable<?, ?>> innerSideJoins(Visit visit) {
        List<QOM.JoinTable<?, ?>> joins = new ArrayList<>();
        List<QueryPart> path = visit.path();
        for (int i = 0; i < path.size() - 1; i++) {
            if (path.get(i) instanceof QOM.JoinTable<?, ?> join
                    && join.$table2() == path.get(i + 1)) {
                joins.add(join);
            }
        }
        return joins;
    }

    /**
     * A driving side named in terms of the schema's relations. A driving side that names a relation
     * directly needs nothing doing; one that names a common table expression names no relation at
     * all, and the rows it drives with are the ones that expression's own body produces, so the
     * name is followed through to that body.
     *
     * <p>Without this a driving side spelled as a fold over a common table expression reports no
     * drivers, which reads as an unknown cardinality and weights at one. That is the wrong answer
     * in the worst place: a fold that assembles a set and then probes a relation for each member of
     * it is exactly the shape a registration is bought for, and the register's largest recorded
     * wins are all spelled that way.
     *
     * @param direct relations named directly on the driving side
     * @param aliases the aliases and expression names on it, which may resolve to relations
     * @param visits every relation-naming visit in the definition, to read an expression's body off
     */
    private static Set<String> resolved(Set<String> direct, Set<String> aliases,
            List<Visit> visits) {
        if (!direct.isEmpty()) {
            return direct;
        }
        Set<String> through = new TreeSet<>();
        for (String alias : aliases) {
            through.addAll(accumulatedOver(alias, visits));
        }
        return through;
    }

    /** The relations under a join's first operand, which are the rows its second is probed for. */
    private static Set<String> drivingSideOf(QOM.JoinTable<?, ?> join, List<Visit> visits) {
        Set<String> driving = new TreeSet<>();
        for (Visit visit : visits) {
            if (visit.descendsThrough(join, join.$table1())) {
                driving.add(visit.name());
            }
        }
        return driving;
    }

    /** The relations a query level's own {@code FROM} introduces, which its rows are drawn from. */
    private static Set<String> boundDirectlyAt(QueryPart level, List<Visit> visits) {
        Set<String> bound = new TreeSet<>();
        for (Visit visit : visits) {
            List<QueryPart> levels = visit.levels();
            if (!levels.isEmpty() && levels.getLast() == level) {
                bound.add(visit.name());
            }
        }
        return bound;
    }

    /** The relations a recursive expression's terms name, which its walk accumulates over. */
    private static Set<String> accumulatedOver(String expression, List<Visit> visits) {
        Set<String> named = new TreeSet<>();
        for (Visit visit : visits) {
            if (visit.ctes().contains(expression)) {
                named.add(visit.name());
            }
        }
        return named;
    }

    /**
     * The query levels that name a relation bound outside themselves. A level binds the aliases and
     * relation names its own {@code FROM} introduces, so a qualifier it uses and does not bind came
     * from an enclosing level, and the engine cannot evaluate the level once for all of them.
     */
    private static Set<QueryPart> correlatedLevels(Map<QueryPart, Set<String>> boundAt,
            Map<QueryPart, Set<String>> qualifiersAt) {
        Set<QueryPart> correlated = Collections.newSetFromMap(new IdentityHashMap<>());
        qualifiersAt.forEach((level, qualifiers) -> {
            Set<String> bound = boundAt.getOrDefault(level, Set.of());
            if (!bound.containsAll(qualifiers)) {
                correlated.add(level);
            }
        });
        return correlated;
    }

    /** The query levels enclosing a part, outermost first. */
    private static List<QueryPart> levelsOf(List<QueryPart> ancestors) {
        return ancestors.stream().filter(part -> part instanceof Select<?>).toList();
    }

    /** The innermost query level enclosing a part, which is the scope its names resolve in. */
    private static Optional<QueryPart> innermostLevel(List<QueryPart> ancestors) {
        List<QueryPart> levels = levelsOf(ancestors);
        return levels.isEmpty()
            ? Optional.empty()
            : Optional.of(levels.getLast());
    }

    /** The names of the common table expressions enclosing a part, outermost first. */
    private static List<String> ctesOf(List<QueryPart> ancestors) {
        return ancestors.stream()
            .filter(part -> part instanceof CommonTableExpression<?>)
            .map(part -> normalize(((CommonTableExpression<?>) part).getUnqualifiedName()))
            .toList();
    }

    /** A name as this walk compares them: last segment, upper case, quoting discarded. */
    private static String normalize(Name name) {
        return name.last().toUpperCase(Locale.ROOT);
    }

    /** The stored definition of one view, as the catalog keeps it after H2's normalization. */
    private static String definitionOf(DSLContext dsl, String viewName) {
        String definition = dsl.select(field(name("VIEW_DEFINITION"), String.class))
            .from(table(name("INFORMATION_SCHEMA", "VIEWS")))
            .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
            .and(field(name("TABLE_NAME"), String.class).eq(viewName.toUpperCase(Locale.ROOT)))
            .fetchOne(0, String.class);
        if (definition == null) {
            throw new IllegalStateException("the catalog holds no stored definition for view "
                + viewName + ", which a definition walk reached");
        }
        return definition;
    }

    /** The parsed form of one stored definition, a parse failure being a defect in the DDL. */
    private static Query parse(DSLContext dsl, String viewName, String definition) {
        try {
            return dsl.parser().parseQuery(definition);
        } catch (RuntimeException e) {
            throw new IllegalStateException("the stored definition of view " + viewName
                + " did not parse, and a definition walk reads it: " + e.getMessage(), e);
        }
    }
}
