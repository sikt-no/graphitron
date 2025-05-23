package fake.code.generated.resolvers.mutation;

import fake.graphql.example.model.SomeError;
import java.lang.Override;
import java.lang.String;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Singleton;
import no.sikt.graphql.exception.DataAccessExceptionContentToErrorMapping;
import no.sikt.graphql.exception.ExceptionToErrorMappingProvider;
import no.sikt.graphql.exception.GenericExceptionContentToErrorMapping;
import no.sikt.graphql.exception.GenericExceptionMappingContent;

@Singleton
public class GeneratedExceptionToErrorMappingProvider implements ExceptionToErrorMappingProvider {
    private final Map<String, List<DataAccessExceptionContentToErrorMapping>> dataAccessMappingsForMutation;

    private final Map<String, List<GenericExceptionContentToErrorMapping>> genericMappingsForMutation;

    public GeneratedExceptionToErrorMappingProvider() {
        dataAccessMappingsForMutation = new HashMap<>();
        genericMappingsForMutation = new HashMap<>();
        var m1 = new GenericExceptionContentToErrorMapping(
                new GenericExceptionMappingContent("java.lang.IllegalArgumentException", null),
                (path, msg) -> new SomeError(path, msg));

        var mutationGenericList = List.of(m1);
        genericMappingsForMutation.put("mutation", mutationGenericList);
    }

    @Override
    public Map<String, List<DataAccessExceptionContentToErrorMapping>> getDataAccessMappingsForMutation() {
        return dataAccessMappingsForMutation;
    }

    @Override
    public Map<String, List<GenericExceptionContentToErrorMapping>> getGenericMappingsForMutation() {
        return genericMappingsForMutation;
    }
}
