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
import no.fellesstudentsystem.graphql.exception.DataAccessExceptionToErrorMappingProvider;

public class GeneratedDataAccessExceptionToErrorMappingProvider implements DataAccessExceptionToErrorMappingProvider {
    private final Map<String, List<DataAccessExceptionContentToErrorMapping>> mappingsForMutation;

    public GeneratedDataAccessExceptionToErrorMappingProvider() {
        mappingsForMutation = new HashMap<>();
        var m1 = new DataAccessExceptionContentToErrorMapping(
                new DataAccessExceptionMappingContent("20997", null),
                path -> new OtherError(path, "This is an error"));
        var m2 = new DataAccessExceptionContentToErrorMapping(
                new DataAccessExceptionMappingContent("20998", "bad word detected"),
                path -> new OtherError(path, null));
        var m3 = new DataAccessExceptionContentToErrorMapping(
                new DataAccessExceptionMappingContent("1337", "data error"),
                path -> new YetAnotherError(path, "This is an error for the union type"));
        var editCustomerWithMultipleErrorsList = List.of(m1, m2, m3);
        mappingsForMutation.put("editCustomerWithMultipleErrors", editCustomerWithMultipleErrorsList);
        var editCustomerWithOtherErrorList = List.of(m1, m2);
        mappingsForMutation.put("editCustomerWithOtherError", editCustomerWithOtherErrorList);
        var editCustomerWithUnionErrorList = List.of(m1, m2, m3);
        mappingsForMutation.put("editCustomerWithUnionError", editCustomerWithUnionErrorList);
    }

    @Override
    public Map<String, List<DataAccessExceptionContentToErrorMapping>> getMappingsForMutation() {
        return mappingsForMutation;
    }
}