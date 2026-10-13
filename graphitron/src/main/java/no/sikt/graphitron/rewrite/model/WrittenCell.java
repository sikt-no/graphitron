package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.model.jooq.ColumnRef;

import java.util.List;
import java.util.Objects;

/**
 * One value cell a DML statement writes, read off the write plan by
 * {@link OperationMember.Write.Dml#writtenCells()}: the wire access path below the input argument
 * ({@code ["title"]}, or {@code ["address", "line1"]} for a leaf under a nested grouping input),
 * the column it writes (the first of the carrier's columns for a composite NodeId carrier, whose
 * columns share one path), and the cell's {@link ColumnWriteBound}.
 */
public record WrittenCell(List<String> inputPath, ColumnRef column, ColumnWriteBound bound) {
    public WrittenCell {
        inputPath = List.copyOf(inputPath);
        if (inputPath.isEmpty()) {
            throw new IllegalArgumentException("WrittenCell requires a non-empty input path");
        }
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(bound, "bound");
    }
}
