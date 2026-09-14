package no.sikt.graphitron.model.capture;

import no.sikt.graphitron.model.Public;
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

import static no.sikt.graphitron.model.Tables.JVM_CLASS;
import static no.sikt.graphitron.model.Tables.JVM_CLASS_SUPERTYPE;
import static no.sikt.graphitron.model.Tables.JVM_DECLARED_TYPE_REF;
import static no.sikt.graphitron.model.Tables.JVM_METHOD;
import static no.sikt.graphitron.model.Tables.JVM_METHOD_PARAMETER;
import static no.sikt.graphitron.model.Tables.JVM_RECORD_COMPONENT;
import static no.sikt.graphitron.model.Tables.META_MATERIALIZE;
import static no.sikt.graphitron.model.Tables.META_RELATION;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;

/**
 * Brings a store that already holds a previous run's rows to the state capture expects, deleting
 * exactly what this run owns and touching nothing else.
 *
 * <h2>This class is dissolving, and what is left of it is a measure of what has not moved</h2>
 *
 * <p>It exists because the walk it prepares for cannot delete anything. A writer that upserts rows
 * keyed by a coordinate has no way to notice a coordinate the author removed, so something else has
 * to empty the partition first, and this is that something. Every gatherer written since is
 * incremental first and marks and sweeps its own rows instead: it stamps what this reading wrote
 * and deletes what carries a different stamp, which needs no pass in front of it and, unlike a
 * clear, leaves a reading that fails with the previous one still standing. A relation whose owner
 * does that needs nothing here, and the way it says so is {@link #SELF_SWEEPING}, read from the
 * declared owner rather than from any list kept here.
 *
 * <p>So this class shrinks as ownership spreads rather than being retired in one move, and what
 * remains is exactly the population with no owner that sweeps. Two arms are left of the three.
 * The wholesale arm is gone, retired below. The graph-scoped arm still covers 205 relations, which
 * is every graph-keyed base table bar the graph's own anchor row, and it goes relation by relation
 * as each gets a declared, sweeping owner. The classpath arm is the {@code jvm_} census's, and it
 * goes with that family, which the {@code code_} gatherers are replacing. There is nothing here to
 * generalise or improve: the work is elsewhere, and this is the residue of it.
 *
 * <h2>What it still does</h2>
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
 * <h2>The wholesale arm, retired</h2>
 *
 * <p>A third arm emptied outright every base relation the other two did not reach, and it was
 * written in exemption polarity on purpose: a relation nobody had thought about was rebuilt rather
 * than silently retained. What it exempted included a hand-written list of the source-partitioned
 * families, and that list is what failed. The schema grew five {@code sql_} relations past it, so
 * five relations keyed by {@code source_name} were being deleted for every source on every warm
 * pass while the catalog walk rewrote only the sources this run owns. The result was a partition
 * kept by halves: a sibling module's {@code sql_table} standing with its {@code sql_node_metadata}
 * and {@code sql_routine} children gone, which no reader can tell from a schema that declares
 * neither. That is precisely the loss the paragraph above forbids, and it went unseen because the
 * case for it asserted over a graph-keyed relation, where the scoping is by a column rather than
 * by a list.
 *
 * <p>The arm is not fixed by extending the list, because the list is the defect. Its whole
 * population turned out to be those five, and the catalog gatherer already deletes all five per
 * owned source, in order, before rewriting them. So the arm was redundant where it was correct and
 * destructive where it was not, and it is gone rather than corrected.
 *
 * <p>The polarity it carried is worth keeping and does not need it. What catches a relation nobody
 * thought about is {@code MetaDeclarationGateTest}: an observed relation with no declared owner and
 * no line on the frozen undeclared roster fails the build. That is a stricter statement than a
 * delete, it fires at build time rather than at a consumer's next read, and it asks for the thing
 * actually wanted, which is an owner.
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

    // The source-partitioned families used to be listed here, so that a wholesale arm below could
    // exempt them. Both are gone; the retirement is argued in the class comment.

    /**
     * The gatherers that end a reading by sweeping it. Every row they write carries the instant it
     * was written at, and the reading ends by deleting the rows carrying a different one, so a
     * relation one of them owns already holds exactly its last reading and there is nothing here to
     * empty. Clearing it anyway would lose a reading with no second chance to be taken: this one
     * runs before the pass, so what it wrote is what a build the pass refuses has left to say.
     *
     * <p>The document gatherer was here until it started running inside the pass, and the exemption
     * was not merely redundant then, it was wrong. A relation it owns keys into the {@code graphql_}
     * anchors, which are the walk's and are cleared, so the clear was deleting a parent out from
     * under an exempt child and the referential failure that followed demoted the run to a private
     * store rather than failing it. Two exempt relations reach it on the vocabulary graphitron
     * itself ships, so the path was every warm pass rather than a corner. Now that the pass rewrites
     * them itself, emptying them is the ordinary discipline again and the contradiction is gone.
     *
     * <p>Named rather than read off the {@code touched_at} column, which is on relations this walk
     * writes too. There the column is one writer's stamp beside another writer's rows, not the
     * whole relation's discipline, and a clear that skipped those would let this walk's rows pile
     * up unbounded.
     */
    private static final Set<String> SELF_SWEEPING = Set.of("code");

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
     * key: the stale owned classpath partitions are peeled off from their leaves inwards, and the
     * graph's own partition is walked in the reverse of the order the sink writes it in.
     *
     * <p>Two arms where there were three. What the third emptied is the catalog gatherer's to
     * empty, per source, and always was.
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
        dsl.deleteFrom(JVM_CLASS_SUPERTYPE)
            .where(JVM_CLASS_SUPERTYPE.SOURCE_NAME.in(staleOwned)).execute();
        dsl.deleteFrom(JVM_CLASS).where(JVM_CLASS.SOURCE_NAME.in(staleOwned)).execute();

        var swept = selfSwept(dsl);
        for (Table<?> table : childrenFirst(graphScoped(swept, refilled(dsl)))) {
            dsl.deleteFrom(table)
                .where(table.field("GRAPH_NAME", String.class).eq(graphName))
                .execute();
        }
    }

    /**
     * The relations a registration refills, which this clear must not empty first because their
     * owner empties them itself. {@code Materializations.refreshPartition} runs
     * {@code DELETE FROM target WHERE graph_name = ?} and then inserts that graph's rows back from
     * the source view, inside this same transaction, so a graph-keyed target cleared here is
     * deleted twice per warm pass and nothing writes it in between: what the hand-written
     * derivations fill is disjoint from this set.
     *
     * <p>This is the wholesale arm's retirement argued at a second population. That arm was
     * redundant where it was correct because the catalog gatherer already deleted per owned source;
     * the same is true here, one owner down.
     *
     * <p>Measured before it was taken, because the number is small and saying so is the honest way
     * to state the case: on the sakila example's warm pass the whole clear is 659 ms over 25705
     * rows and this subset is 24 ms over 2780. The case is that a clear should not do an owner's
     * work, not that this is fast; a consumer store's targets are its largest relations and nobody
     * has measured it there.
     *
     * <p><b>Why exempting these cannot repeat the defect exempting the document gatherer's did.</b>
     * That exemption retained rows whose parents the clear went on to empty, so the delete met a
     * foreign key and the run was demoted. Every one of these targets declares exactly one foreign
     * key and it is to {@code store_graph}, which is the one relation this clear never deletes. A
     * retained row here can have no cleared parent.
     */
    private static Set<String> refilled(DSLContext dsl) {
        return dsl.select(META_MATERIALIZE.TARGET_TABLE_NAME)
            .from(META_MATERIALIZE)
            .fetchSet(META_MATERIALIZE.TARGET_TABLE_NAME);
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
     * default. Three exclusions. {@code store_graph} itself, whose anchor row upserts with a fresh
     * {@code last_captured} and is never deleted, while its recipe children rewrite fresh every run
     * and so clear here with the rest. The self-swept relations, which arrive already holding
     * exactly their last reading. And the registered materialization targets, for the reason
     * {@link #refilled} states.
     */
    private static Set<Table<?>> graphScoped(Set<String> swept, Set<String> refilled) {
        var tables = new LinkedHashSet<Table<?>>();
        for (Table<?> table : Public.PUBLIC.getTables()) {
            String name = table.getName().toLowerCase(java.util.Locale.ROOT);
            if (table.getOptions().type() == TableOptions.TableType.VIEW
                || table.equals(STORE_GRAPH)
                || swept.contains(name)
                || refilled.contains(name)
                || table.field("GRAPH_NAME", String.class) == null) {
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
