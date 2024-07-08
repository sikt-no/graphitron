package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInput;
import graphql.GraphQLError;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.validation.RecordValidator;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class EditInputJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInput> editInput, String path,
                                                RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (editInput != null) {
            for (var itEditInput : editInput) {
                if (itEditInput == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                var editInput_name = itEditInput.getName();
                if (editInput_name != null) {
                    if (arguments.contains(pathHere + "name/firstName")) {
                        customerRecord.setFirstName(editInput_name.getFirstName());
                    }
                    if (arguments.contains(pathHere + "name/surname")) {
                        customerRecord.setLastName(editInput_name.getSurname());
                    }
                }
                if (arguments.contains(pathHere + "id")) {
                    customerRecord.setId(itEditInput.getId());
                }
                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }

    public static Set<GraphQLError> validate(List<CustomerRecord> customerRecordList, String path,
                                             RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var env = transform.getEnv();
        var validationErrors = new HashSet<GraphQLError>();

        for (int itCustomerRecordListIndex = 0; itCustomerRecordListIndex < customerRecordList.size(); itCustomerRecordListIndex++) {
            var itCustomerRecord = customerRecordList.get(itCustomerRecordListIndex);
            var pathsForProperties = new HashMap<String, String>();
            if (arguments.contains(pathHere + "name/firstName")) {
                pathsForProperties.put("firstName", pathHere + itCustomerRecordListIndex + "/name/firstName");
            }
            if (arguments.contains(pathHere + "name/surname")) {
                pathsForProperties.put("lastName", pathHere + itCustomerRecordListIndex + "/name/surname");
            }
            if (arguments.contains(pathHere + "id")) {
                pathsForProperties.put("id", pathHere + itCustomerRecordListIndex + "/id");
            }
            validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(itCustomerRecord, pathsForProperties, env));
        }

        return validationErrors;
    }
}
