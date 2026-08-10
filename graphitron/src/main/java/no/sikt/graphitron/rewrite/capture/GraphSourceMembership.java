package no.sikt.graphitron.rewrite.capture;

import static no.sikt.graphitron.model.Tables.STORE_GRAPH_SOURCE;

/**
 * Records the run's graph-to-source membership: one {@code store_graph_source} row per source the
 * capture actually read, every kind alike. The relation is what makes an SDL-to-catalog join
 * determinate in a shared store (the {@code store_graph} comment's recorded question), so the
 * notes sit at the three places a run enumerates its sources (the SDL walk's file census, the
 * catalog walk's schema packages, and the classpath scan's entries) and fire regardless of
 * whether the source's own row was claimed by this run, because a retained partition is still
 * this graph's read.
 *
 * <p>Rows go through the sink like other graph-keyed rows (graph-stamped, first-wins deduped,
 * flushed in FK order); the graph-scoped clear empties the previous run's membership by the
 * {@code graph_name} column like every graph-keyed relation, so a source the run stops reading
 * drops out without bookkeeping here.
 */
final class GraphSourceMembership {

    private GraphSourceMembership() {}

    /**
     * Notes one source as read by this run's graph. A {@code null} name is the scan's hand-built
     * stand-in population and records against the empty source, matching its class rows.
     */
    static void note(FactSink sink, String sourceName) {
        String name = sourceName == null ? "" : sourceName;
        if (!sink.claim(STORE_GRAPH_SOURCE, name)) {
            return;
        }
        var record = sink.dsl().newRecord(STORE_GRAPH_SOURCE);
        record.setSourceName(name);
        sink.add(record);
    }
}
