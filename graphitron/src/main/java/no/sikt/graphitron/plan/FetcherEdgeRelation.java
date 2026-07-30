package no.sikt.graphitron.plan;

import graphql.schema.FieldCoordinates;
import no.sikt.graphitron.command.FetcherEdgeCommand;

import java.util.List;

/**
 * The fetcher edge relation of one generation run: one row per coordinate of the covered
 * non-launcher families whose emitted fetcher methods reference other generated units (see
 * {@link FetcherEdgeCommands} for the declared families). Keyed by the coordinate alone; a
 * producer minting two rows for one coordinate fails here. Deliberately disjoint from the
 * launcher relation's coordinate keys: a coordinate whose references ride a launcher row's
 * source, WHERE or result slots gets no row in this relation.
 */
public record FetcherEdgeRelation(List<FetcherEdgeCommand> rows) {

    public FetcherEdgeRelation {
        rows = List.copyOf(rows);
        long distinctKeys = rows.stream().map(FetcherEdgeCommand::coordinate).distinct().count();
        if (distinctKeys != rows.size()) {
            throw new IllegalArgumentException(
                "the fetcher edge relation is keyed by coordinate; a coordinate appeared twice");
        }
    }

    /** The relation's coordinate keys, for the membership enforcer and disjointness pins. */
    public List<FieldCoordinates> coordinates() {
        return rows.stream().map(FetcherEdgeCommand::coordinate).toList();
    }
}
