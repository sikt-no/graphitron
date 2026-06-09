package no.sikt.graphitron.rewrite.classifieddsl;

import java.util.List;

/**
 * The two-axis classification verdict R281's corpus asserts: a {@code producer} pipeline (a list of
 * {@link ProducerStep}, length &le; 2, empty meaning "inline, no new execution") and a {@code mapping}
 * ({@link Mapping}). This is the dimensional fingerprint the {@code @classified} directive carries
 * and the {@link LeafTupleAdapter} produces from today's sealed leaves.
 *
 * <p>The tuple is the primary fingerprint, not the complete emit key: the derived facts
 * (source-context, fetcher/loader mechanism, dispatch batching, error channel) live in slots beside
 * these two axes, so two leaves differing only in a slot share one tuple. See
 * {@code roadmap/classification-test-dsl.md} §"The leaf-to-tuple adapter".
 */
public record DimensionTuple(List<ProducerStep> producer, Mapping mapping) {

    public DimensionTuple {
        producer = List.copyOf(producer);
        if (producer.size() > 2) {
            throw new IllegalArgumentException(
                "producer pipeline length must be <= 2; got " + producer);
        }
    }

    /** The empty (inline, no new execution) pipeline with the given mapping. */
    static DimensionTuple inline(Mapping mapping) {
        return new DimensionTuple(List.of(), mapping);
    }

    /** A single-step pipeline. */
    static DimensionTuple of(ProducerStep step, Mapping mapping) {
        return new DimensionTuple(List.of(step), mapping);
    }

    /** A two-step pipeline. */
    static DimensionTuple of(ProducerStep first, ProducerStep second, Mapping mapping) {
        return new DimensionTuple(List.of(first, second), mapping);
    }
}
