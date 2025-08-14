package no.sikt.graphql.helpers.resolvers;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
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
}
