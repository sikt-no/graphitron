package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.DeleteInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class DeleteInputJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<DeleteInput> deleteInput, String path,
                                                RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (deleteInput != null) {
            for (var itDeleteInput : deleteInput) {
                if (itDeleteInput == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "email")) {
                    customerRecord.setEmail(itDeleteInput.getEmail());
                }
                if (arguments.contains(pathHere + "id")) {
                    customerRecord.setId(itDeleteInput.getId());
                }
                if (arguments.contains(pathHere + "firstName")) {
                    customerRecord.setFirstName(itDeleteInput.getFirstName());
                }
                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}
