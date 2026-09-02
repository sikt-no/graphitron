package no.sikt.graphitron.model.capture.compile;

import no.sikt.graphitron.model.tables.records.JavacDiagnosticRecord;
import no.sikt.graphitron.model.run.GraphIdentity;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static no.sikt.graphitron.model.Tables.JAVAC_DIAGNOSTIC;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import no.sikt.graphitron.model.compile.CompileDiagnostic;
import no.sikt.graphitron.model.compile.CompileRound;

/**
 * The {@code javac_} family's writer: transcribes a published {@link CompileRound} into the fact
 * store's {@code javac_diagnostic} relation, verbatim and wholesale. The relation's content
 * contract is exactly the published round, so this class inherits, and does not fix, the
 * round-scoped-list semantics: publishing a round replaces the graph's previous round entirely,
 * which is also how a resolved failure is cleared.
 *
 * <p>For this family the store is a delivery channel, not a cache, which is why the writer takes
 * the dev session's live store handle instead of opening its own per round: a round written into
 * a store the session's readers never see is a round the diagnostics surface answers wrongly
 * about. Every statement is scoped to the session's graph; in a store shared by a workspace's
 * modules an unscoped delete is one dev session erasing a sibling module's diagnostics. One
 * transaction per round (delete the graph's rows, insert the round's list) is what stands in for
 * a completeness stamp: no handle ever observes half a round.
 *
 * <p>Store trouble costs warmth, never the dev loop: a write the store rejects (jOOQ's
 * {@link DataAccessException}, the wrapper every statement- and transaction-level failure
 * reaches this class in) logs and returns, the console and workspace sinks having already
 * carried the round. Anything else escaping {@link #write} is a bug in this class, not store
 * trouble, and is deliberately not swallowed.
 */
public final class CompileFacts {

    private static final Logger LOG = LoggerFactory.getLogger(CompileFacts.class);

    private final DSLContext dsl;
    private final GraphIdentity graph;
    private boolean ownershipWarned;

    /**
     * @param dsl   the dev session's store handle; live, shared with the session's in-process
     *              readers, never a per-round open of the writer's own
     * @param graph the session's graph: the partition every statement is scoped by, and the base
     *              directory the graph's ownership is checked against
     */
    public CompileFacts(DSLContext dsl, GraphIdentity graph) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.graph = Objects.requireNonNull(graph, "graph");
    }

    /** Replaces the graph's {@code javac_diagnostic} partition with {@code round}'s published list. */
    public void write(CompileRound round) {
        try {
            dsl.transaction(tx -> writeRound(tx.dsl(), round));
        } catch (DataAccessException e) {
            LOG.warn("javac diagnostics for graph '{}' could not be written to the fact store; "
                + "store-side readers answer without this round", graph.name(), e);
        }
    }

    private void writeRound(DSLContext tx, CompileRound round) {
        String recorded = tx.select(STORE_GRAPH.BASE_DIR).from(STORE_GRAPH)
            .where(STORE_GRAPH.GRAPH_NAME.eq(graph.name()))
            .fetchOne(0, String.class);
        if (recorded == null) {
            // No capture ever reached this store under this graph (the in-memory session
            // fallback, or a session that has not generated yet). The anchor is ambient for a
            // partitioned row, so mint the minimal one; a concurrent capture's richer upsert
            // wins thereafter, which is what onDuplicateKeyIgnore leaves room for. last_captured
            // is NOT NULL and owed a value even though no capture happened: the write time is
            // the only time this store knows, and it overstates nothing a reader can reach,
            // because a mint happens only where capture never ran, which is the in-memory store
            // no eviction surface will ever see; on a shared file the first real capture
            // replaces the whole row.
            tx.insertInto(STORE_GRAPH)
                .set(STORE_GRAPH.GRAPH_NAME, graph.name())
                .set(STORE_GRAPH.BASE_DIR, graph.baseDir().toString())
                .set(STORE_GRAPH.LAST_CAPTURED, LocalDateTime.now())
                .onDuplicateKeyIgnore()
                .execute();
        } else if (!recorded.equals(graph.baseDir().toString())) {
            // Same rule as capture's ownership check: a graph name recorded against another
            // checkout's directory is not this session's partition to touch.
            if (!ownershipWarned) {
                ownershipWarned = true;
                LOG.warn("graph '{}' is recorded in the shared fact store for {}, but this dev "
                        + "session's base directory is {}. Leaving that partition's javac "
                        + "diagnostics alone; set <graphName> so the two modules stop claiming "
                        + "one name.",
                    graph.name(), recorded, graph.baseDir());
            }
            return;
        }
        tx.deleteFrom(JAVAC_DIAGNOSTIC)
            .where(JAVAC_DIAGNOSTIC.GRAPH_NAME.eq(graph.name()))
            .execute();
        Map<String, Integer> ordinals = new HashMap<>();
        List<JavacDiagnosticRecord> records = new ArrayList<>(round.diagnostics().size());
        for (CompileDiagnostic diagnostic : round.diagnostics()) {
            var record = tx.newRecord(JAVAC_DIAGNOSTIC);
            record.setGraphName(graph.name());
            record.setFile(diagnostic.file());
            record.setLineNumber(diagnostic.line());
            record.setColumnNumber(diagnostic.column());
            record.setOrdinal(ordinals.merge(
                diagnostic.file() + '\0' + diagnostic.line() + '\0' + diagnostic.column(),
                1, Integer::sum) - 1);
            record.setKind(diagnostic.kind());
            record.setCode(diagnostic.code());
            record.setMessage(diagnostic.message());
            records.add(record);
        }
        if (!records.isEmpty()) {
            tx.batchInsert(records).execute();
        }
    }
}
