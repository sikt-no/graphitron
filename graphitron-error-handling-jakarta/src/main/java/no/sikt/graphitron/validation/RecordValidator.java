package no.sikt.graphitron.validation;


import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.schema.DataFetchingEnvironment;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.jooq.Record;

import java.util.*;
import java.util.stream.Collectors;

public class RecordValidator {


    /**
     * Validates the properties of a record and returns any validation errors as GraphQL errors.
     *
     * @param record                The record to validate.
     * @param pathsForProperties    A map of property paths to their corresponding graphql paths.
     * @param env                   The DataFetchingEnvironment.
     * @param <T>                   The type of the record.
     * @return A set of GraphQL errors representing validation violations.
     */
    public static <T extends Record> Set<GraphQLError> validatePropertiesAndGenerateGraphQLErrors(T record, Map<String, String> pathsForProperties, DataFetchingEnvironment env) {
        var violations = validateProperties(record, pathsForProperties.keySet());

        if (!violations.isEmpty()) {
            return violations.stream().map(it -> {
                List<Object> path = new ArrayList<>(env.getExecutionStepInfo().getPath().toList());
                path.addAll(List.of(pathsForProperties.getOrDefault(it.getPropertyPath().toString(), "undefined").split("/")));
                return GraphqlErrorBuilder.newError(env)
                        .path(path)
                        .message(it.getMessage())
                        .errorType(ErrorType.ValidationError)
                        .build();
            }).collect(Collectors.toSet());
        }
        return new HashSet<>();
    }

    /**
     * Validates the specified properties of a record.
     *
     * @param record                The record to validate.
     * @param propertiesToValidate  The names of properties to validate.
     * @param <T>                   The type of the record.
     * @return A set of ConstraintViolation objects representing validation violations.
     */
    public static <T extends Record> Set<ConstraintViolation<T>> validateProperties(T record, Collection<String> propertiesToValidate) {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            return propertiesToValidate.stream()
                    .map(property -> validator.validateProperty(record, property))
                    .flatMap(Collection::stream)
                    .collect(Collectors.toCollection(HashSet::new));
        }
    }
}
