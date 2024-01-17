package fake.code.generated.exception;

import fake.graphql.example.package.model.OtherError;
import java.lang.Override;
import java.lang.String;
import java.util.ArrayList;
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

        var editCustomerWithOtherErrorList = new ArrayList<DataAccessExceptionContentToErrorMapping>();
        editCustomerWithOtherErrorList.add(
                new DataAccessExceptionContentToErrorMapping(
                        new DataAccessExceptionMappingContent("20997", null),
                        path -> new OtherError(path, "This is an error")));
        editCustomerWithOtherErrorList.add(
                new DataAccessExceptionContentToErrorMapping(
                        new DataAccessExceptionMappingContent("20998", "bad word detected"),
                        path -> new OtherError(path, null)));
        mappingsForMutation.put("editCustomerWithOtherError", editCustomerWithOtherErrorList);

        var editCustomerWithUnionErrorList = new ArrayList<DataAccessExceptionContentToErrorMapping>();
        editCustomerWithUnionErrorList.add(
                new DataAccessExceptionContentToErrorMapping(
                        new DataAccessExceptionMappingContent( "1337", "data error"),
                        path -> new OtherError(path, "This is an error for the union type")));
        mappingsForMutation.put("editCustomerWithUnionError", editCustomerWithUnionErrorList);
    }
    @Override
    public Map<String, List<DataAccessExceptionContentToErrorMapping>> getMappingsForMutation() {
        return mappingsForMutation;
    }
}
