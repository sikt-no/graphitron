package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInputLevel2A;
import graphql.GraphQLError;
import java.lang.String;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphql.helpers.validation.RecordValidator;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class EditInputLevel2AJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel2A> editInputLevel2A,
                                                String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel2A != null) {
            for (var itEditInputLevel2A : editInputLevel2A) {
                if (itEditInputLevel2A == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "firstName")) {
                    customerRecord.setFirstName(itEditInputLevel2A.getFirstName());
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
            if (arguments.contains(pathHere + "firstName")) {
                pathsForProperties.put("firstName", pathHere + itCustomerRecordListIndex + "/firstName");
            }
            validationErrors.addAll(RecordValidator.validatePropertiesAndGenerateGraphQLErrors(itCustomerRecord, pathsForProperties, env));
        }

        return validationErrors;
    }
}
