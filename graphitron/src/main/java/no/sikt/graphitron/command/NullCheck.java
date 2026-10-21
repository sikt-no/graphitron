package no.sikt.graphitron.command;

import no.sikt.graphitron.model.jooq.ColumnRef;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Whether a single-record launcher hands its fetched record on as it is, or returns {@code null}
 * when the record holds nothing under the presence rule of
 * {@link no.sikt.graphitron.rewrite.model.Presence}. It states the decision, not only the columns
 * to project: the launcher renders over the command with no schema in hand, and projects the
 * check's columns and probe whatever the query selects, so the decision is
 * selection-independent.
 *
 * <p>Rides {@link ResultShape.SingleRecord} because the fact co-varies with cardinality: a list
 * or a connection has no single object to collapse.
 */
public sealed interface NullCheck {

    /** The record is returned as fetched: the field is non-null, or its type never collapses. */
    record None() implements NullCheck {}

    /**
     * The record is {@code null} unless some leaf has every column non-null or the probe column is
     * set.
     *
     * @param leaves the type's column leaves, each absent when any of its columns is null; a
     *               composite {@code @nodeId} is one leaf of several columns
     * @param probe  the existence test over the references and lists the type holds, correlated
     *               on the row the record is read from; empty when the type holds none
     */
    record Collapses(List<List<ColumnRef>> leaves, Optional<PresenceProbe> probe) implements NullCheck {
        public Collapses {
            leaves = leaves.stream().map(List::copyOf).toList();
            Objects.requireNonNull(probe, "probe");
            if (leaves.isEmpty() && probe.isEmpty()) {
                throw new IllegalArgumentException(
                    "a collapsing null check carries a column leaf or a probe; with neither the record"
                    + " is never present, which no type yields");
            }
        }

        /** The distinct columns of every leaf, the ones the launcher adds to its select list. */
        public List<ColumnRef> columns() {
            return leaves.stream().flatMap(List::stream).distinct().toList();
        }
    }
}
