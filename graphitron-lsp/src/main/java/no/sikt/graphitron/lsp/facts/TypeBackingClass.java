package no.sikt.graphitron.lsp.facts;

import no.sikt.graphitron.model.read.StoreHandle;
import org.jooq.TableField;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_CONFLICT;
import static no.sikt.graphitron.model.Tables.INTENT_TYPE_BACKING_SEED;

/**
 * Which Java class stands for an SDL type, for a surface that must name one. The rows are
 * {@code intent_type_backing}'s, the coalesced answer over the {@code @table} binding read through
 * its table's record and the closure over producer returns and accessor hops; what a reader makes
 * of more than one of them is this class.
 *
 * <p>The relation prefers no row over another, so both rules below are the reader's and are stated
 * here rather than assumed of the store.
 *
 * <ul>
 *   <li><b>A grounding beats a hop.</b> A class a producer of the type's own delivers is a row of
 *       {@code intent_type_backing_seed}; a class reached by reading the type off another type's
 *       member is not. A hop reads the parent's member type without checking it against the child's
 *       own grounding, so where the two disagree the hop is wrong rather than merely second, and
 *       the answer is drawn from the seeds whenever the type has any.</li>
 *   <li><b>A type still contested has no answer.</b> Two producers naming different classes leave
 *       nothing to prefer between them, and a surface that guessed would offer one class's members
 *       while the generator bound the other. Empty, which every caller already renders as silence.
 *       That is also what the walk this replaces does with the same population, by refusing to bind
 *       at all once its observations disagree.</li>
 * </ul>
 *
 * <p>Empty is likewise what an unbacked type gives, and the two absences are deliberately one
 * answer here: a surface asking what a class offers has nothing to say in either case. A surface
 * that does want them apart, because it is explaining a type rather than resolving one, asks
 * {@link #contested} for the second, which is where {@code intent_type_backing_conflict}'s arity
 * turns into words.
 *
 * <p>This is the question the language server used to put to the classification walk's projection,
 * which resolved it by reflection per build and carried the answer as a permit's class name.
 */
public final class TypeBackingClass {

    private TypeBackingClass() {}

    /**
     * The class backing {@code typeName} in this graph, empty where the store reaches none or
     * reaches more than one after the grounding rule.
     */
    public static Optional<String> of(StoreHandle store, String typeName) {
        return Optional.ofNullable(ofTypes(store, List.of(typeName)).get(typeName));
    }

    /**
     * The class backing each of {@code typeNames}, keyed by type name, absent for a type the store
     * reaches none or more than one class for. The two rules above are applied per type, so a
     * contested type is missing from the result beside an unbacked one exactly as it is from
     * {@link #of}.
     *
     * <p>Bulk because the inlay arm annotates a whole visible region, and a query per declaration
     * on the screen is the cost the claim readers already refuse to pay. Two queries and not one
     * per type: the seeds for everything asked about, then the coalesced relation for the names
     * nothing grounded, which is every type whose backing is a hop's or a {@code @table}
     * binding's.
     */
    public static Map<String, String> ofTypes(StoreHandle store, Collection<String> typeNames) {
        if (typeNames.isEmpty()) return Map.of();
        var grounded = candidatesByType(store, INTENT_TYPE_BACKING_SEED.TYPE_NAME,
            INTENT_TYPE_BACKING_SEED.CLASS_NAME, INTENT_TYPE_BACKING_SEED.GRAPH_NAME, typeNames);
        var ungrounded = new ArrayList<String>();
        for (String typeName : typeNames) {
            if (!grounded.containsKey(typeName)) ungrounded.add(typeName);
        }
        var reached = candidatesByType(store, INTENT_TYPE_BACKING.TYPE_NAME,
            INTENT_TYPE_BACKING.CLASS_NAME, INTENT_TYPE_BACKING.GRAPH_NAME, ungrounded);
        var resolved = new LinkedHashMap<String, String>();
        for (String typeName : typeNames) {
            var candidates = grounded.containsKey(typeName)
                ? grounded.get(typeName)
                : reached.getOrDefault(typeName, Set.of());
            if (candidates.size() == 1) resolved.put(typeName, candidates.iterator().next());
        }
        return resolved;
    }

    /**
     * The classes the store answers with for a type it answers more than one way, canonically
     * rendered, or empty for a type it does not. Over the coalesced relation, which is the grain
     * {@code intent_type_backing_conflict} groups, so a type this reports is one
     * {@link #of} declines rather than a second reading of the same rows: a type one seed grounds
     * has an answer even where hops reached others.
     */
    public static Optional<String> contested(StoreHandle store, String typeName) {
        if (of(store, typeName).isPresent()) return Optional.empty();
        return Optional.ofNullable(store.dsl()
            .select(INTENT_TYPE_BACKING_CONFLICT.CLASS_NAMES)
            .from(INTENT_TYPE_BACKING_CONFLICT)
            .where(INTENT_TYPE_BACKING_CONFLICT.GRAPH_NAME.eq(store.graphName()))
            .and(INTENT_TYPE_BACKING_CONFLICT.TYPE_NAME.eq(typeName))
            .fetchOne(INTENT_TYPE_BACKING_CONFLICT.CLASS_NAMES));
    }

    private static Map<String, Set<String>> candidatesByType(
        StoreHandle store, TableField<?, String> typeColumn, TableField<?, String> classColumn,
        TableField<?, String> graphColumn, Collection<String> typeNames
    ) {
        if (typeNames.isEmpty()) return Map.of();
        var byType = new LinkedHashMap<String, Set<String>>();
        store.dsl()
            .selectDistinct(typeColumn, classColumn)
            .from(typeColumn.getTable())
            .where(graphColumn.eq(store.graphName()))
            .and(typeColumn.in(typeNames))
            .fetch()
            .forEach(row -> byType
                .computeIfAbsent(row.get(typeColumn), ignored -> new LinkedHashSet<>())
                .add(row.get(classColumn)));
        return byType;
    }
}
