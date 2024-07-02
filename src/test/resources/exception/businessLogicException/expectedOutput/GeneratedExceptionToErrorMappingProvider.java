package fake.code.generated.exception;

import fake.graphql.example.model.OtherError;
import fake.graphql.example.model.YetAnotherError;
import java.lang.Override;
import java.lang.String;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Singleton;
import no.fellesstudentsystem.graphql.exception.DataAccessExceptionContentToErrorMapping;
import no.fellesstudentsystem.graphql.exception.DataAccessExceptionMappingContent;
import no.fellesstudentsystem.graphql.exception.ExceptionToErrorMappingProvider;
import no.fellesstudentsystem.graphql.exception.GenericExceptionContentToErrorMapping;
import no.fellesstudentsystem.graphql.exception.GenericExceptionMappingContent;

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
        var m2 = new GenericExceptionContentToErrorMapping(
                new GenericExceptionMappingContent("java.lang.UnsupportedOperationException", null),
                (path, msg) -> new OtherError(path, "This is an error"));
        var m3 = new GenericExceptionContentToErrorMapping(
                new GenericExceptionMappingContent("java.net.BindException", "bad word detected"),
                (path, msg) -> new OtherError(path, msg));
        var m4 = new GenericExceptionContentToErrorMapping(
                new GenericExceptionMappingContent("java.security.GeneralSecurityException", "data error"),
                (path, msg) -> new YetAnotherError(path, "This is an error for the union type"));

        var editCustomerWithMultipleErrorsDatabaseList = List.of(m1);

        var editCustomerWithMultipleErrorsGenericList = List.of(m2, m3, m4);
        dataAccessMappingsForMutation.put("editCustomerWithMultipleErrors", editCustomerWithMultipleErrorsDatabaseList);
        genericMappingsForMutation.put("editCustomerWithMultipleErrors", editCustomerWithMultipleErrorsGenericList);

        var editCustomerWithOtherErrorDatabaseList = List.of(m1);

        var editCustomerWithOtherErrorGenericList = List.of(m2, m3);
        dataAccessMappingsForMutation.put("editCustomerWithOtherError", editCustomerWithOtherErrorDatabaseList);
        genericMappingsForMutation.put("editCustomerWithOtherError", editCustomerWithOtherErrorGenericList);

        var editCustomerWithUnionErrorDatabaseList = List.of(m1);

        var editCustomerWithUnionErrorGenericList = List.of(m2, m3, m4);
        dataAccessMappingsForMutation.put("editCustomerWithUnionError", editCustomerWithUnionErrorDatabaseList);
        genericMappingsForMutation.put("editCustomerWithUnionError", editCustomerWithUnionErrorGenericList);
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