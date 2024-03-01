package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInputLevel3;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class EditInputLevel3JOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel3> editInputLevel3, String path,
                                                RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel3 != null) {
            for (var itEditInputLevel3 : editInputLevel3) {
                if (itEditInputLevel3 == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "email")) {
                    customerRecord.setEmail(itEditInputLevel3.getEmail());
                }
                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}
