package no.sikt.graphitron.model.capture;

import no.sikt.graphitron.model.Public;
import no.sikt.graphitron.model.capture.java.JavaSourceFacts;
import no.sikt.graphitron.model.classpath.CompletionData;
import no.sikt.graphitron.model.sink.FactSink;
import no.sikt.graphitron.model.sources.ClasspathSources;
import org.jooq.DSLContext;
import org.jooq.Table;
import org.jooq.TableOptions;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.JAVA_CLASS_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_FIELD_DECLARATION;
import static no.sikt.graphitron.model.Tables.JAVA_FILE;
import static no.sikt.graphitron.model.Tables.JAVA_METHOD_DECLARATION;
import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.JVM_CLASS_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.JVM_DECLARED_TYPE_REF;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.JVM_RECORD_COMPONENT;
import static no.sikt.graphitron.model.Tables.JVM_SCALAR_TYPE_FIELD;
import static no.sikt.graphitron.model.Tables.META_RELATION;
import static no.sikt.graphitron.model.Tables.SQL_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_CONSTRAINT_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_ENUM_BINDING;
import static no.sikt.graphitron.model.Tables.SQL_INDEX;
import static no.sikt.graphitron.model.Tables.SQL_INDEX_COLUMN;
import static no.sikt.graphitron.model.Tables.SQL_PRIMARY_KEY;
import static no.sikt.graphitron.model.Tables.SQL_REFERENTIAL_CONSTRAINT;
import static no.sikt.graphitron.model.Tables.SQL_SCHEMA;
import static no.sikt.graphitron.model.Tables.SQL_TABLE;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static no.sikt.graphitron.model.Tables.STORE_STAMP;

/**
 * Brings a store that already holds a previous run's rows to the state capture expects, deleting
 * exactly what this run owns and touching nothing else.
 *
 * <p>Owned means two things, and excludes a third. The run's <em>graph</em>: every graph-keyed
 * relation clears scoped to this run's {@code graph_name} and rebuilds whole, because within one
 * graph the parse it rebuilds from is paid for regardless, and other graphs' rows are another run's
 * business. And the run's <em>crawled sources</em>: for each classpath entry in this run's input
 * set, the stamp decides retain-or-rewrite; a source not in the input set is never examined and
 * never deleted, because a jar absent from this module's classpath may be another graph's live
 * dependency. (The jOOQ package's partition is cleared by the catalog walk itself, which is where
 * the owned package first becomes known; schema-file source rows are taken over by upsert in the SDL
 * walk.) {@code store_source} and {@code store_graph} rows upsert with fresh {@code last_seen} /
 * {@code last_captured} stamps and are never deleted by a run that does not own them.
 *
 * <p>What it excludes is the relations a self-sweeping gatherer declares itself the owner of, which
 * need no clear and must not get one; {@link #SELF_SWEEPING} says why.
 *
 * <p>A classpath partition survives when {@code store_source} recorded a content hash for it and
 * the entry still hashes to that. A directory root is never stamped, because it changes on every
 * compile; the SDL families are re-walked from a parse the pipeline pays for anyway.
 *
 * <p>Retention is enforced by pre-claiming rather than by filtering the walk. {@link FactSink#claim}
 * is already the gate every class passes through, so seeding it with the class names the surviving
 * partitions hold extends the store's first-wins rule across runs: capture walks exactly as it
 * would cold, and the rows it would have re-inserted are dropped where duplicates always are. The
 * seed is scoped to the surviving partitions of <em>this run's</em> sources: a sibling graph's
 * partition holding the same class name under a source this run never crawled must not block this
 * run's own row, the two coexisting being precisely what the source-led key buys.
 */
final class StoreRefresh {

    /**
     * The source-partitioned families: relations whose rows survive by source rather than being
     * cleared wholesale or by graph. Listed rather than derived: a relation added here without a
     * matching delete (in {@link #clear} for {@code jvm_}, in the catalog walk for {@code sql_},
     * in {@link JavaSourceFacts} for {@code java_}) would keep rows whose partition went away,
     * which is what the "an empty refresh empties every relation" anchor exists to catch.
     *
     * <p>The {@code java_} family is here for a reason the other two do not share: capture never
     * writes it at all. Its writer runs on the {@code .java} cadence and owns both halves of its
     * own lifecycle, so a generator round has nothing to retain or rewrite there, and a round that
     * cleared it would blank every module's Javadoc and positions on a cadence that has nothing to
     * do with sources changing.
     */
    private static final Set<Table<?>> PARTITIONED = Set.of(
        JVM_CLASS, JVM_CLASS_SUPERTYPE, JVM_METHOD, JVM_METHOD_PARAMETER,
        JVM_RECORD_COMPONENT, JVM_DECLARED_TYPE_REF,
        JVM_SCALAR_TYPE_FIELD,
        SQL_SCHEMA, SQL_TABLE, SQL_COLUMN, SQL_ENUM_BINDING, SQL_CONSTRAINT, SQL_CONSTRAINT_COLUMN,
        SQL_PRIMARY_KEY,
        SQL_REFERENTIAL_CONSTRAINT, SQL_INDEX, SQL_INDEX_COLUMN,
        JAVA_FILE, JAVA_CLASS_DECLARATION, JAVA_METHOD_DECLARATION, JAVA_FIELD_DECLARATION);

    /**
     * The gatherers that end a reading by sweeping it. Every row they write carries the instant it
     * was written at, and the reading ends by deleting the rows carrying a different one, so a
     * relation one of them owns already holds exactly its last reading and there is nothing here to
     * empty. Clearing it anyway would lose a reading with no second chance to be taken: these run
     * before the pass, so what they wrote is what a build the pass refuses has left to say.
     *
     * <p>Named rather than read off the {@code touched_at} column, which is on relations this walk
     * writes too. There the column is one writer's stamp beside another writer's rows, not the
     * whole relation's discipline, and a clear that skipped those would let this walk's rows pile
     * up unbounded.
     */
    private static final Set<String> SELF_SWEEPING = Set.of("document", "classpath");

    private StoreRefresh() {}

    /**
     * Deletes what this run is about to rewrite and claims what it is about to skip.
     *
     * @param graphName  the partition this run owns; the graph-keyed clear's scope
     * @param extensions the class census this run will offer, read only for the classpath entries
     *                   it names; the entries decide which partitions can survive
     */
    static void prepare(FactSink sink, ClasspathSources sources,
                        List<CompletionData.ExternalReference> extensions, String graphName) {
        DSLContext dsl = sink.dsl();
        Set<String> named = namedSources(extensions);
        Set<String> fresh = freshSources(dsl, sources, extensions);
        clear(dsl, graphName, named, fresh);
        if (!fresh.isEmpty()) {
            dsl.update(STORE_SOURCE)
                .set(STORE_SOURCE.LAST_SEEN, LocalDateTime.now())
                .where(STORE_SOURCE.SOURCE_NAME.in(fresh))
                .execute();
        }
        for (String source : fresh) {
            sink.claim(STORE_SOURCE, source);
        }
        for (String className : dsl.select(JVM_CLASS.CLASS_NAME).from(JVM_CLASS)
                .where(JVM_CLASS.SOURCE_NAME.in(fresh)).fetch(0, String.class)) {
            sink.claim(JVM_CLASS, className);
        }
    }

    /**
     * The recorded sources this run can prove unchanged: still named by the census, still hashing to
     * what the store recorded. A source whose stamp is null never qualifies, which covers both the
     * kinds that have no useful hash and a partition whose load died before it could be stamped.
     * The map of recorded stamps carries schema files too now that capture stamps them, and none of
     * them can reach the fresh set: the only candidates tested are the census's names, which no
     * schema-file path is a member of. That matters beyond the {@code jvm_} claims this set seeds,
     * because it also gates the {@code last_seen} refresh above, so the reason is the census filter
     * and not anything about which family reads the set.
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
     * Empties what this run owns, children before parents throughout so no delete trips a foreign
     * key: the stale owned classpath partitions are peeled off from their leaves inwards, the
     * graph's own partition is walked in the reverse of the order the sink writes it in, and the
     * wholesale remainder the same way.
     */
    private static void clear(DSLContext dsl, String graphName, Set<String> named, Set<String> fresh) {
        var staleOwned = new LinkedHashSet<>(named);
        staleOwned.removeAll(fresh);
        dsl.deleteFrom(JVM_DECLARED_TYPE_REF)
            .where(JVM_DECLARED_TYPE_REF.SOURCE_NAME.in(staleOwned)).execute();
        dsl.deleteFrom(JVM_METHOD_PARAMETER)
            .where(JVM_METHOD_PARAMETER.SOURCE_NAME.in(staleOwned)).execute();
        dsl.deleteFrom(JVM_METHOD).where(JVM_METHOD.SOURCE_NAME.in(staleOwned)).execute();
        dsl.deleteFrom(JVM_RECORD_COMPONENT)
            .where(JVM_RECORD_COMPONENT.SOURCE_NAME.in(staleOwned)).execute();
        dsl.deleteFrom(JVM_SCALAR_TYPE_FIELD)
            .where(JVM_SCALAR_TYPE_FIELD.SOURCE_NAME.in(staleOwned)).execute();
        dsl.deleteFrom(JVM_CLASS_SUPERTYPE)
            .where(JVM_CLASS_SUPERTYPE.SOURCE_NAME.in(staleOwned)).execute();
        dsl.deleteFrom(JVM_CLASS).where(JVM_CLASS.SOURCE_NAME.in(staleOwned)).execute();

        var swept = selfSwept(dsl);
        for (Table<?> table : childrenFirst(graphScoped(swept))) {
            dsl.deleteFrom(table)
                .where(table.field("GRAPH_NAME", String.class).eq(graphName))
                .execute();
        }
        for (Table<?> table : childrenFirst(wholesale(swept))) {
            dsl.deleteFrom(table).execute();
        }
    }

    /**
     * The relations a {@link #SELF_SWEEPING} gatherer declares itself the owner of, lowercased to
     * match the census. Asked of {@code meta_relation} rather than listed, so a relation moved
     * between gatherers moves its lifecycle with it, and a relation nobody declared stays this
     * walk's to empty.
     */
    private static Set<String> selfSwept(DSLContext dsl) {
        return dsl.select(META_RELATION.RELATION_NAME)
            .from(META_RELATION)
            .where(META_RELATION.OWNER_NAME.in(SELF_SWEEPING))
            .fetchSet(META_RELATION.RELATION_NAME);
    }

    /**
     * Every relation carrying the graph dimension, whose clear scopes to this run's graph. Derived
     * from the column rather than listed, so a new graph-keyed relation is ownership-scoped by
     * default. Two exclusions. {@code store_graph} itself, whose anchor row upserts with a fresh
     * {@code last_captured} and is never deleted, while its recipe children rewrite fresh every run
     * and so clear here with the rest. And the self-swept relations, which arrive already holding
     * exactly their last reading.
     */
    private static Set<Table<?>> graphScoped(Set<String> swept) {
        var tables = new LinkedHashSet<Table<?>>();
        for (Table<?> table : Public.PUBLIC.getTables()) {
            if (table.getOptions().type() == TableOptions.TableType.VIEW
                || table.equals(STORE_GRAPH)
                || swept.contains(table.getName().toLowerCase(java.util.Locale.ROOT))
                || table.field("GRAPH_NAME", String.class) == null) {
                continue;
            }
            tables.add(table);
        }
        return tables;
    }

    /**
     * Every base relation the clear still empties outright: the generated relations less the
     * views, which hold no rows of their own, less the graph-scoped set above, less the
     * source-partitioned families, less the self-swept ones, less the three {@code store_}
     * relations whose lifetimes this class is deciding ({@code store_graph}'s recipe children are
     * graph-scoped by their column and clear there), and less the {@code meta_} family. Written in
     * exemption polarity on purpose: a relation nobody thought about is emptied and rebuilt, never
     * silently retained, which is why the self-swept ones are exempted by a declaration they carry
     * rather than by the shape of a column.
     *
     * <p>The {@code meta_} exemption is the one whose reason is not about cadence. Those rows are
     * the schema's description of itself, authored in the DDL and supplied by it, so no run
     * rewrites them and a clear would simply lose them: the family's views cannot be emptied at
     * all and were skipped as views, but the register is a base table and would have been. A
     * warm store would then hold the schema with its own description missing, which reads as a
     * store that registers nothing rather than as one that was cleared.
     */
    private static Set<Table<?>> wholesale(Set<String> swept) {
        var graphScoped = graphScoped(swept);
        var tables = new LinkedHashSet<Table<?>>();
        for (Table<?> table : Public.PUBLIC.getTables()) {
            if (table.getOptions().type() == TableOptions.TableType.VIEW
                || PARTITIONED.contains(table)
                || graphScoped.contains(table)
                || swept.contains(table.getName().toLowerCase(java.util.Locale.ROOT))
                || table.getName().toLowerCase(java.util.Locale.ROOT).startsWith("meta_")
                || table.equals(STORE_GRAPH)
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
