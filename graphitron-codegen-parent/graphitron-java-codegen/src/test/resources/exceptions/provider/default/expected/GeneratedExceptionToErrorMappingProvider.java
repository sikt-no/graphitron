package fake.code.generated.resolvers.mutation;

import fake.graphql.example.model.SomeError;
import java.lang.Override;
import java.lang.String;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import no.sikt.graphql.exception.DataAccessExceptionContentToErrorMapping;
import no.sikt.graphql.exception.ExceptionToErrorMappingProvider;
import no.sikt.graphql.exception.GenericExceptionContentToErrorMapping;
import no.sikt.graphql.exception.GenericExceptionMatcher;

public class GeneratedExceptionToErrorMappingProvider implements ExceptionToErrorMappingProvider {
    private final Map<String, List<DataAccessExceptionContentToErrorMapping>> dataAccessMappingsForOperation;

    private final Map<String, List<GenericExceptionContentToErrorMapping>> genericMappingsForOperation;

    public GeneratedExceptionToErrorMappingProvider() {
        dataAccessMappingsForOperation = new HashMap<>();
        genericMappingsForOperation = new HashMap<>();
        var m1 = new GenericExceptionContentToErrorMapping(
                new GenericExceptionMatcher("java.lang.IllegalArgumentException", null),
                (path, msg) -> new SomeError(path, msg));

        var mutationGenericList = List.of(m1);
        genericMappingsForOperation.put("mutation", mutationGenericList);
    }

    @Override
    public Map<String, List<DataAccessExceptionContentToErrorMapping>> getDataAccessMappingsForOperation() {
        return dataAccessMappingsForOperation;
    }

    @Override
    public Map<String, List<GenericExceptionContentToErrorMapping>> getGenericMappingsForOperation() {
        return genericMappingsForOperation;
    }
}
