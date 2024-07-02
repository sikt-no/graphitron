package fake.code.generated.exception;

import fake.graphql.example.model.OtherError;
import fake.graphql.example.model.YetAnotherError;
import java.lang.Override;
import java.lang.String;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import no.fellesstudentsystem.graphql.exception.DataAccessExceptionContentToErrorMapping;
import no.fellesstudentsystem.graphql.exception.DataAccessExceptionMappingContent;
import no.fellesstudentsystem.graphql.exception.ExceptionToErrorMappingProvider;
import no.fellesstudentsystem.graphql.exception.GenericExceptionContentToErrorMapping;

import javax.inject.Singleton;

@Singleton
public class GeneratedExceptionToErrorMappingProvider implements ExceptionToErrorMappingProvider {
    private final Map<String, List<DataAccessExceptionContentToErrorMapping>> dataAccessMappingsForMutation;

    private final Map<String, List<GenericExceptionContentToErrorMapping>> genericMappingsForMutation;

    public GeneratedExceptionToErrorMappingProvider() {
        dataAccessMappingsForMutation = new HashMap<>();
        genericMappingsForMutation = new HashMap<>();
        var m1 = new DataAccessExceptionContentToErrorMapping(
                new DataAccessExceptionMappingContent("20997", null),
                (path, msg) -> new OtherError(path, "This is an error"));
        var m2 = new DataAccessExceptionContentToErrorMapping(
                new DataAccessExceptionMappingContent("20998", "bad word detected"),
                (path, msg) -> new OtherError(path, msg));
        var m3 = new DataAccessExceptionContentToErrorMapping(
                new DataAccessExceptionMappingContent("1337", "data error"),
                (path, msg) -> new YetAnotherError(path, "This is an error for the union type"));

        var editCustomerWithMultipleErrorsDatabaseList = List.of(m1, m2, m3);
        dataAccessMappingsForMutation.put("editCustomerWithMultipleErrors", editCustomerWithMultipleErrorsDatabaseList);

        var editCustomerWithOtherErrorDatabaseList = List.of(m1, m2);
        dataAccessMappingsForMutation.put("editCustomerWithOtherError", editCustomerWithOtherErrorDatabaseList);

        var editCustomerWithUnionErrorDatabaseList = List.of(m1, m2, m3);
        dataAccessMappingsForMutation.put("editCustomerWithUnionError", editCustomerWithUnionErrorDatabaseList);
    }

    @Override
    public Map<String, List<DataAccessExceptionContentToErrorMapping>> getDataAccessMappingsForMutation(
    ) {
        return dataAccessMappingsForMutation;
    }

    @Override
    public Map<String, List<GenericExceptionContentToErrorMapping>> getGenericMappingsForMutation(
    ) {
        return genericMappingsForMutation;
    }
}