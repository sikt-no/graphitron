package no.sikt.graphql.helpers.mappers;

import no.sikt.graphql.NodeIdStrategy;
import org.jooq.Field;
import org.jooq.UpdatableRecord;
import org.jooq.impl.UpdatableRecordImpl;

import java.util.function.Function;

public class MapperHelper {

    public static <T extends UpdatableRecord<T>> void validateOverlappingNodeIdColumns(
            NodeIdStrategy nodeIdStrategy,
            String nodeIdValue,
            T targetRecord,
            String typeId,
            Field<?> column,
            String overlappingColumnName,
            Function<T, Object> getterLambda
    ) {
        var firstValue = getterLambda.apply(targetRecord);
        if (firstValue != null) {
            T newJooqRecord = targetRecord.copy();
            nodeIdStrategy.setReferenceId((UpdatableRecordImpl<?>) newJooqRecord, nodeIdValue, typeId, column);
            if (!firstValue.equals(getterLambda.apply(newJooqRecord))) {
                throw new IllegalArgumentException(String.format("Conflicting values for column: %s", overlappingColumnName));
            }
        }
    }
}
