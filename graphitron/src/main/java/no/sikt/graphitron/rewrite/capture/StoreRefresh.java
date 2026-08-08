package no.sikt.graphitron.rewrite.capture;

import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.rewrite.catalog.CompletionData;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.JVM_RECORD_COMPONENT;
import static no.sikt.graphitron.model.Tables.JVM_SCALAR_TYPE_FIELD;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_STAMP;

/**
 * Brings a store that already holds a previous run's rows to the state capture expects: everything
 * a walk is about to rewrite is gone, and everything a walk can prove unchanged is left alone.
 *
 * <p>What survives is decided per source, which is the only granularity the schema supports and the
 * reason persistence could not be bolted on afterwards. A partition survives when {@code
 * store_source} recorded a content hash for it and the entry still hashes to that, which today is
 * true of exactly one family: the classpath jars behind {@code jvm_}, which slice-4's measurement
 * makes the expensive part of a load. A directory root is never stamped, because it changes on
 * every compile; a jOOQ schema package has no cheap hash and a walk that costs milliseconds; and
 * the SDL families are re-walked from a parse the pipeline pays for anyway. All three are therefore
 * cleared wholesale, and the clear is written that way round on purpose: a relation nobody thought
 * about is emptied and rebuilt, never silently retained.
 *
 * <p>Retention is enforced by pre-claiming rather than by filtering the walk. {@link FactSink#claim}
 * is already the gate every class passes through, so seeding it with the class names a surviving
 * partition holds extends the store's first-wins rule across runs: capture walks exactly as it
 * would cold, and the rows it would have re-inserted are dropped where duplicates always are. That
 * also settles what would otherwise be a real hazard, a class that appears in two classpath entries
 * whose order changed between runs: the previous run's answer wins and the load stays legal,
 * instead of a retained partition colliding with a freshly walked one on the primary key.
 */
final class StoreRefresh {

    /**
     * The relations a surviving partition retains rows in. Everything reachable from a {@code
     * jvm_class} row, listed rather than derived: a relation added here without a matching delete
     * below would keep rows whose parent went away, which is what the "an empty refresh empties
     * every relation" anchor exists to catch.
     */
    private static final Set<Table<?>> PARTITIONED = Set.of(
        JVM_CLASS, JVM_METHOD, JVM_METHOD_PARAMETER, JVM_RECORD_COMPONENT, JVM_SCALAR_TYPE_FIELD);

    private StoreRefresh() {}

    /**
     * Deletes what this run is about to rewrite and claims what it is about to skip.
     *
     * @param extensions the class census this run will offer, read only for the classpath entries
     *                   it names; the entries decide which partitions can survive
     */
    static void prepare(FactSink sink, ClasspathSources sources,
                        List<CompletionData.ExternalReference> extensions) {
        DSLContext dsl = sink.dsl();
        Set<String> fresh = freshSources(dsl, sources, extensions);
        clear(dsl, fresh);
        for (String source : fresh) {
            sink.claim(STORE_SOURCE, source);
        }
        for (String className : dsl.select(JVM_CLASS.CLASS_NAME).from(JVM_CLASS).fetch(0, String.class)) {
            sink.claim(JVM_CLASS, className);
        }
    }

    /**
     * The recorded sources this run can prove unchanged: still named by the census, still hashing to
     * what the store recorded. A source whose stamp is null never qualifies, which covers both the
     * kinds that have no useful hash and a partition whose load died before it could be stamped.
     */
    private static Set<String> freshSources(DSLContext dsl, ClasspathSources sources,
                                            List<CompletionData.ExternalReference> extensions) {
        Map<String, String> recorded = new LinkedHashMap<>();
        dsl.select(STORE_SOURCE.SOURCE_NAME, STORE_SOURCE.STAMP)
            .from(STORE_SOURCE)
            .where(STORE_SOURCE.STAMP.isNotNull())
            .forEach(row -> recorded.put(row.value1(), row.value2()));
        if (recorded.isEmpty()) {
            return Set.of();
        }
        var fresh = new LinkedHashSet<String>();
        for (String name : namedSources(extensions)) {
            String stamp = recorded.get(name);
            if (stamp == null) {
                continue;
            }
            Path entry = Path.of(name);
            if (Files.isRegularFile(entry) && stamp.equals(sources.stamp(entry))) {
                fresh.add(name);
            }
        }
        return fresh;
    }

    private static Set<String> namedSources(List<CompletionData.ExternalReference> extensions) {
        var names = new LinkedHashSet<String>();
        for (CompletionData.ExternalReference reference : extensions) {
            String name = reference.sourceName();
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Empties every relation except the rows the surviving partitions own. Children go before
     * parents throughout, so no delete trips a foreign key: the partitioned family is peeled off
     * from its leaves inwards, and the rest is walked in the reverse of the order the sink writes
     * it in, which is the same declared-key topology read the other way.
     */
    private static void clear(DSLContext dsl, Set<String> fresh) {
        var stale = dsl.select(JVM_CLASS.CLASS_NAME).from(JVM_CLASS)
            .where(JVM_CLASS.SOURCE_NAME.notIn(fresh));
        dsl.deleteFrom(JVM_METHOD_PARAMETER).where(JVM_METHOD_PARAMETER.CLASS_NAME.in(stale)).execute();
        dsl.deleteFrom(JVM_METHOD).where(JVM_METHOD.CLASS_NAME.in(stale)).execute();
        dsl.deleteFrom(JVM_RECORD_COMPONENT).where(JVM_RECORD_COMPONENT.CLASS_NAME.in(stale)).execute();
        dsl.deleteFrom(JVM_SCALAR_TYPE_FIELD).where(JVM_SCALAR_TYPE_FIELD.CLASS_NAME.in(stale)).execute();
        dsl.deleteFrom(JVM_CLASS).where(JVM_CLASS.SOURCE_NAME.notIn(fresh)).execute();

        for (Table<?> table : childrenFirst(wholesale())) {
            dsl.deleteFrom(table).execute();
        }
        dsl.deleteFrom(STORE_SOURCE).where(STORE_SOURCE.SOURCE_NAME.notIn(fresh)).execute();
    }

    /**
     * Every base relation the clear empties outright: the generated relations less the views, which
     * hold no rows of their own, less the partitioned family and the two {@code store_} relations,
     * whose lifetimes this class is deciding.
     */
    private static Set<Table<?>> wholesale() {
        var tables = new LinkedHashSet<Table<?>>();
        for (Table<?> table : Public.PUBLIC.getTables()) {
            if (table.getOptions().type() == TableOptions.TableType.VIEW
                || PARTITIONED.contains(table)
                || table.equals(STORE_SOURCE)
                || table.equals(STORE_STAMP)) {
                continue;
            }
            tables.add(table);
        }
        return tables;
    }

    private static List<Table<?>> childrenFirst(Set<Table<?>> tables) {
        var ordered = new ArrayList<>(FactSink.parentsFirst(tables));
        Collections.reverse(ordered);
        return ordered;
    }
}
