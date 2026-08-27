package no.sikt.graphitron.model.test;

import no.sikt.graphitron.model.derive.Materializations;
import org.jooq.DSLContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * The register's refresh depth: the registrations grouped into the stages a refresh must run them
 * in, a registration sitting one stage below the deepest registration whose target its view reads.
 *
 * <p>What {@link Materializations#refreshOrder} flattens. That order is a sequence a serial pass
 * executes, and its alphabetical tie-break deliberately interleaves independent registrations with
 * dependent ones, so the sequence cannot be re-cut into stages after the fact: a registration that
 * needs nothing can legitimately land after one that waits on three. The stages are the other
 * reading of the same rows, and the one that says what waits on what.
 *
 * <p>Two questions want them, which is why this is a harness rather than a line inside either
 * caller. A gate pins the depth, so that a registration cannot change the register's shape without
 * a figure being edited in the same commit. And a reading of the refresh's own per-registration
 * timings wants the stage each registration sits in, because the pass is serial while the stages
 * are not: the sum over stages of the dearest registration in each is what a perfectly parallel
 * refresh of the same registrations would cost, and the difference against the serial total is what
 * the ordering is worth as a bound rather than as a wait anybody pays today.
 *
 * <p>Test scope, and it belongs here rather than beside {@link Materializations}: a refresh executes
 * a sequence and has no use for the partition, so main carrying it would be main carrying an
 * accessor only tests call. The rows it reads are the same rows the refresh order reads, written by
 * {@code MaterializeDependencies}, so the two cannot come to disagree about what waits on what.
 */
public final class RefreshStages {

    private RefreshStages() {}

    /**
     * The registrations of {@code dsl}'s register, grouped by stage: index 0 holds those whose views
     * read no registered target at all, and index <i>k</i> those whose deepest prerequisite sits in
     * stage <i>k</i>-1. Alphabetical within a stage, on {@link Materializations#registrations}'
     * order, so the grouping is deterministic run to run.
     *
     * @return one list per stage, in order; empty where the register is
     * @throws IllegalStateException if the dependency rows contain a cycle, which no stage
     *         assignment could settle; {@link Materializations#refreshOrder} is where that failure
     *         is stated in full, and a caller wanting the cycle named should call it
     */
    public static List<List<Materializations.Registration>> of(DSLContext dsl) {
        List<Materializations.Registration> census = Materializations.registrations(dsl);
        var unmet = new LinkedHashMap<String, TreeSet<String>>();
        census.forEach(r -> unmet.put(r.sourceViewName(), new TreeSet<>()));
        dsl.select(field(name("SOURCE_VIEW_NAME"), String.class),
                field(name("DEPENDS_ON"), String.class))
            .from(table(name("META_MATERIALIZE_DEPENDENCY")))
            .fetch()
            .forEach(row -> unmet.get(row.value1()).add(row.value2()));

        Map<String, Integer> stageOf = new HashMap<>();
        int stage = 0;
        while (stageOf.size() < census.size()) {
            var ready = unmet.entrySet().stream()
                .filter(entry -> !stageOf.containsKey(entry.getKey()))
                .filter(entry -> stageOf.keySet().containsAll(entry.getValue()))
                .map(Map.Entry::getKey)
                .toList();
            if (ready.isEmpty()) {
                throw new IllegalStateException("the materialization registry's derived dependencies"
                    + " admit no stage assignment, every remaining registration waiting on another;"
                    + " Materializations.refreshOrder names the cycle");
            }
            for (String registration : ready) {
                stageOf.put(registration, stage);
            }
            stage++;
        }

        var stages = new ArrayList<List<Materializations.Registration>>(stage);
        for (int index = 0; index < stage; index++) {
            int current = index;
            stages.add(census.stream()
                .filter(registration -> stageOf.get(registration.sourceViewName()) == current)
                .toList());
        }
        return List.copyOf(stages);
    }

    /**
     * How many stages the register's refresh takes: the length of its longest chain of
     * registrations, each waiting on the last. One for a register whose views read no registered
     * target, zero for an empty one.
     */
    public static int depth(DSLContext dsl) {
        return of(dsl).size();
    }
}
