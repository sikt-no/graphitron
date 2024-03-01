package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInputLevel1;
import graphql.GraphQLError;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.validation.RecordValidator;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class EditInputLevel1JOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel1> editInputLevel1, String path,
                                                RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel1 != null) {
            for (var itEditInputLevel1 : editInputLevel1) {
                if (itEditInputLevel1 == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                var editInputLevel1_editC1 = itEditInputLevel1.getEditC1();
                if (editInputLevel1_editC1 != null) {
                    if (arguments.contains(pathHere + "editC1/lastName")) {
                        customerRecord.setLastName(editInputLevel1_editC1.getLastName());
                    }
                }
                if (arguments.contains(pathHere + "id")) {
                    customerRecord.setId(itEditInputLevel1.getId());
                }
                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }

    public static Set<GraphQLError> validate(List<CustomerRecord> customerRecordList,
                                             String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var env = transform.getEnv();
        var validationErrors = new HashSet<GraphQLError>();

        for (int itCustomerRecordListIndex = 0; itCustomerRecordListIndex < customerRecordList.size(); itCustomerRecordListIndex++) {
            var itCustomerRecord = customerRecordList.get(itCustomerRecordListIndex);
            var pathsForProperties = new HashMap<String, String>();
            if (arguments.contains(pathHere + "editC1/lastName")) {
                pathsForProperties.put("lastName", pathHere + itCustomerRecordListIndex + "/editC1/lastName");
            }
            if (arguments.contains(pathHere + "id")) {
                pathsForProperties.put("id", pathHere + itCustomerRecordListIndex + "/id");
            }
            validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(itCustomerRecord, pathsForProperties, env));
        }

        return validationErrors;
    }
}
