package no.fellesstudentsystem.graphql.helpers.query;

import org.jooq.Index;
import org.jooq.SortField;
import org.jooq.SortOrder;

import java.util.List;
import java.util.stream.Collectors;

public class QueryHelper {

    public static List<SortField<?>> getSortFields(List<Index> indexes, String selectedIndexName, String sortOrder) {
        return indexes.stream()
                .filter(it -> it.getName().equals(selectedIndexName))
                .findFirst()
                .orElseThrow()
                .getFields().stream()
                .map(field -> field.$sortOrder(sortOrder.equalsIgnoreCase("ASC") ? SortOrder.ASC : SortOrder.DESC))
                .collect(Collectors.toList());
    }
}
