package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
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
                if (arguments.contains(pathHere + "postalCode")) {
                    customerRecord.setPostalCode(itEditInput.getPostalCode());
                }
                if (arguments.contains(pathHere + "id")) {
                    customerRecord.setId(itEditInput.getId());
                }
                if (arguments.contains(pathHere + "firstName")) {
                    customerRecord.setFirstName(itEditInput.getFirstName());
                }
                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}
