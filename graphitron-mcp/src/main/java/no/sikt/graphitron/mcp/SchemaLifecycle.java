package no.sikt.graphitron.mcp;

import no.sikt.graphitron.model.read.StoreHandle;

import java.util.Optional;

import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_PROBLEM;
import static no.sikt.graphitron.model.Tables.STORE_GRAPH;
import static org.jooq.impl.DSL.exists;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.selectOne;

/**
 * The two axes every store-backed answer reports alongside itself: whether the store holds this
 * graph at all, and whether the newest read of its schema refused anything. Two orthogonal
 * questions rather than one tri-state, so a reader can tell "nothing has captured this graph" from
 * "the facts are here and the newest edit did not parse".
 *
 * <p>Both are rows. Availability is the presence of the graph's {@code store_graph} anchor, the row
 * every graph-keyed foreign key lands on, so its presence is the store holding this graph and its
 * absence is nothing having captured it. Freshness is the emptiness of
 * {@code graphql_schema_problem} over the graph's partition: it is written on every pass, on either
 * outcome, so no rows means the document was read clean and rows mean the newest read refused
 * something while the transcription families still hold what the parseable sources yielded. That is
 * what {@code Previous} has always meant, and it is what the families do per source anyway.
 *
 * <p>One statement carrying two {@code EXISTS} predicates, one per axis. The freshness axis was an
 * or over two relations while the parser's refusals and the document-wide stages' were kept apart;
 * one relation records all three stages now, with the stage a column, and which stage refused is no
 * business of this record's. Nothing here needs a count either: the axes turn on whether a partition
 * is empty, and a reader that counted refusals would be paying for a number the {@code diagnostics}
 * tools already answer properly.
 *
 * <p>Two readings of the axes are worth stating because a caller could expect otherwise. A graph
 * whose anchor a diagnostics loader minted before any schema capture reads as available, which is
 * the anchor's own meaning: the store does hold that graph, its javac diagnostics are readable, and
 * the freshness axis speaks about the schema read rather than about every fact under the graph. And
 * a first pass that met a refusal reads as available with previous facts, where a reader off a
 * projection minted only on success reported nothing available at all. The store genuinely holds
 * every fact the parseable sources yielded, so answering as well as the facts allow and saying how
 * current they are beats declining to answer.
 *
 * <p>What is deliberately not an axis is whether a capture is running this instant. That is process
 * state with a crash-shaped failure mode, no tool's answer turns on it beyond "may refresh
 * shortly", so no relation carries it and nothing hands it in either. Server liveness is not an
 * axis for the opposite reason: a tool that answers has proved it.
 *
 * @param captured whether the store holds this graph's anchor
 * @param refused whether the newest read of this graph's schema refused a source or a document
 */
record SchemaLifecycle(boolean captured, boolean refused) {

    /**
     * Reads both axes for the handle's graph in one statement.
     *
     * <p>Takes the handle rather than a {@code DSLContext} and a name, so a caller reading inside a
     * reader's transaction (the {@code schema} tool's shape) reports axes from the same commit its
     * payload came from, and a caller whose answer is one query off the session handle reads them
     * beside it.
     */
    static SchemaLifecycle read(StoreHandle store) {
        var row = store.dsl()
            .select(
                field(exists(selectOne()
                    .from(STORE_GRAPH)
                    .where(STORE_GRAPH.GRAPH_NAME.eq(store.graphName())))),
                field(exists(selectOne()
                    .from(GRAPHQL_SCHEMA_PROBLEM)
                    .where(GRAPHQL_SCHEMA_PROBLEM.GRAPH_NAME.eq(store.graphName())))))
            .fetchSingle();
        return new SchemaLifecycle(row.value1(), row.value2());
    }

    /** The availability axis as the wire spells it. */
    String availability() {
        return captured ? "Built" : "Unavailable";
    }

    /**
     * The freshness axis as the wire spells it, absent where nothing has captured this graph: there
     * is no freshness of facts that are not there, which is why every caller omits the field rather
     * than sending it null.
     */
    Optional<String> freshness() {
        return captured ? Optional.of(refused ? "Previous" : "Current") : Optional.empty();
    }
}
