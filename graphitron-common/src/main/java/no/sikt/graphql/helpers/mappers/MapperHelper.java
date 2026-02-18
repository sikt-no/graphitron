package no.sikt.graphql.helpers.mappers;

import no.sikt.graphql.NodeIdStrategy;
import org.jooq.Field;
import org.jooq.UpdatableRecord;
import org.jooq.impl.UpdatableRecordImpl;

import java.util.List;
import java.util.function.Function;

public class MapperHelper {

    /**
     * Validates that all non-null lists have the same size.
     * Used for merged listed @nodeId fields targeting the same jOOQ record list.
     * @param lists The lists to validate
     * @throws IllegalArgumentException if lists have different sizes
     */
    @SafeVarargs
    public static void validateListedNodeIdLengths(List<?>... lists) {
        int expectedSize = -1;
        for (var list : lists) {
            if (list != null) {
                if (expectedSize == -1) {
                    expectedSize = list.size();
                } else if (list.size() != expectedSize) {
                    throw new IllegalArgumentException(
                        "Listed @nodeId fields targeting the same record must have the same length. " +
                        "Expected " + expectedSize + " but got " + list.size());
                }
            }
        }
    }

    public static <T extends UpdatableRecord<T>> void validateOverlappingNodeIdColumns(
            NodeIdStrategy nodeIdStrategy,
            String nodeIdValue,
            T targetRecord,
            String typeId,
            String overlappingColumnName,
            Function<T, Object> getterLambda,
            Field<?>... columns
    ) {
        var firstValue = getterLambda.apply(targetRecord);
        if (firstValue != null) {
            T newJooqRecord = targetRecord.copy();
            nodeIdStrategy.setReferenceId((UpdatableRecordImpl<?>) newJooqRecord, nodeIdValue, typeId, columns);
            if (!firstValue.equals(getterLambda.apply(newJooqRecord))) {
                throw new IllegalArgumentException(String.format("Conflicting values for column: %s", overlappingColumnName));
            }
        }
    }
}
