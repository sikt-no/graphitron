package no.sikt.graphql.helpers.resolvers;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphql.helpers.EnvironmentUtils;
import no.sikt.graphql.helpers.selection.SelectionSet;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

public class ResolverHelpers {
    public static int getPageSize(Integer first, int max, int defaultMax) {
        return Optional.ofNullable(first).map(it -> Math.min(max, it)).orElse(defaultMax);
    }

    @NotNull
    public static SelectionSet getSelectionSet(DataFetchingEnvironment env) {
        return new SelectionSet(EnvironmentUtils.getSelectionSetsFromEnvironment(env));
    }

    public static List<String> formatString(List<?> l) {
        return l.stream().map(it -> it != null ? it.toString() : "null").collect(Collectors.toList());
    }

    public static <T> T transformDTO(Object data, Class<T> targetClass) {
        return new ObjectMapper().convertValue(data, targetClass);
    }

    public static <T> List<T> transformDTOList(Object data, Class<T> targetClass) {
        var mapper = new ObjectMapper();
        return ((List<Object>) data).stream().map(it -> mapper.convertValue(it, targetClass)).toList();
    }
}
