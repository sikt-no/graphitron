package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInputLevel1;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
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
}
