package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.FetcherEdgeCommand;

import java.util.List;
import java.util.Objects;

/**
 * The fetcher edge relation of one generation run: one row per coordinate of the covered
 * non-launcher families whose emitted fetcher methods reference other generated units (see
 * {@link FetcherEdgeCommands} for the declared families). Keyed by the coordinate alone; a
 * producer minting two rows for one coordinate fails at construction, in the
 * {@link CoordinateIndex} every coordinate-keyed relation holds. Deliberately disjoint from the
 * launcher relation's coordinate keys: a coordinate whose references ride a launcher row's
 * source, WHERE or result slots gets no row in this relation.
 */
public record FetcherEdgeRelation(CoordinateIndex<FetcherEdgeCommand> index) {

    /** The relation over {@code rows}, indexed on the coordinate key it is declared to have. */
    public FetcherEdgeRelation(List<FetcherEdgeCommand> rows) {
        this(CoordinateIndex.of(rows, FetcherEdgeCommand::coordinate, "fetcher edge"));
    }

    public FetcherEdgeRelation {
        Objects.requireNonNull(index, "index");
    }

    /** The rows in producer order. */
    public List<FetcherEdgeCommand> rows() {
        return index.rows();
    }

    /** The relation's coordinate keys, for the membership enforcer and disjointness pins. */
    public List<FieldCoordinates> coordinates() {
        return index.coordinates();
    }
}
