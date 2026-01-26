package no.sikt.graphql.helpers.resolvers;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class ResolverHelpers {
    private final static ObjectMapper MAPPER = new ObjectMapper();
    static {
        MAPPER.findAndRegisterModules(); // We really don't want to re-run this every time we map an object.
    }

    public static int getPageSize(Integer first, int max, int defaultMax) {
        return Optional.ofNullable(first).map(it -> Math.min(max, it)).orElse(defaultMax);
    }

    public static List<String> formatString(List<?> l) {
        return l.stream().map(it -> it != null ? it.toString() : "null").collect(Collectors.toList());
    }

    public static List<String> formatString(Object o) {
        return formatString(List.of(o));
    }

    public static <T> T transformDTO(Object data, Class<T> targetClass) {
        if (data == null) {
            return null;
        }

        return MAPPER.convertValue(data, targetClass);
    }

    public static <T> List<T> transformDTOList(Object data, Class<T> targetClass) {
        if (data == null) {
            return null;
        }

        return ((List<Object>) data).stream().map(it -> MAPPER.convertValue(it, targetClass)).toList();
    }

    /**
     * Validates that all non-null values in the map are equal.
     * Used to ensure multiple GraphQL fields mapping to the same database column have consistent values.
     *
     * @param fieldNameToValue map of GraphQL field names to their values (must preserve insertion order)
     * @throws IllegalArgumentException if two non-null values differ
     */
    public static void assertSameColumnValues(Map<String, Object> fieldNameToValue) {
        var nonNullEntries = fieldNameToValue.entrySet().stream()
                .filter(e -> e.getValue() != null)
                .toList();

        if (nonNullEntries.size() < 2) {
            return;
        }

        var firstEntry = nonNullEntries.get(0);
        var conflictingEntry = nonNullEntries.stream()
                .skip(1)
                .filter(e -> !e.getValue().equals(firstEntry.getValue()))
                .findFirst()
                .orElse(null);

        if (conflictingEntry != null) {
            throw new IllegalArgumentException(
                    "Input fields " + firstEntry.getKey() + " and " + conflictingEntry.getKey() +
                    " have conflicting values. This usually indicates that the ids correspond to different entities.");
        }
    }

    /**
     * Validates that all non-null values are equal.
     * Accepts alternating field names and values, preserving the order for deterministic error messages.
     *
     * @param keysAndValues alternating field names (String) and values (Object), e.g. "field1", value1, "field2", value2
     * @throws IllegalArgumentException if two non-null values differ, or if argument count is not even
     */
    public static void assertSameColumnValues(Object... keysAndValues) {
        if (keysAndValues.length % 2 != 0) {
            throw new IllegalArgumentException("Expected even number of arguments (key-value pairs)");
        }
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        assertSameColumnValues(map);
    }
}
