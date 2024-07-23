package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import graphql.GraphQLError;
import java.lang.String;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import no.sikt.graphitron.validation.RecordValidator;

public class CustomerJOOQMapper {
    public static Set<GraphQLError> validate(List<CustomerRecord> customerRecordList, String path,
                                             RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var env = transform.getEnv();
        var validationErrors = new HashSet<GraphQLError>();

        for (int itCustomerRecordListIndex = 0; itCustomerRecordListIndex < customerRecordList.size(); itCustomerRecordListIndex++) {
            var itCustomerRecord = customerRecordList.get(itCustomerRecordListIndex);
            var pathsForProperties = new HashMap<String, String>();
            if (arguments.contains(pathHere + "id")) {
                pathsForProperties.put("id", pathHere + itCustomerRecordListIndex + "/id");
            }
            if (arguments.contains(pathHere + "first")) {
                pathsForProperties.put("firstName", pathHere + itCustomerRecordListIndex + "/first");
            }
            validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(itCustomerRecord, pathsForProperties, env));
        }

        return validationErrors;
    }
}
