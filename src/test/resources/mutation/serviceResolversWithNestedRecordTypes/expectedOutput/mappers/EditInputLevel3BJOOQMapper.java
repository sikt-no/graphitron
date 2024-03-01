package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInputLevel3B;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class EditInputLevel3BJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel3B> editInputLevel3B,
                                                String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel3B != null) {
            for (var itEditInputLevel3B : editInputLevel3B) {
                if (itEditInputLevel3B == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "lastName")) {
                    customerRecord.setLastName(itEditInputLevel3B.getLastName());
                }
                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}
