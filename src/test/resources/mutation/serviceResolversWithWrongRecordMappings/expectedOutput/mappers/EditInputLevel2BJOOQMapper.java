package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInputLevel2B;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class EditInputLevel2BJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel2B> editInputLevel2B,
                                                String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel2B != null) {
            for (var itEditInputLevel2B : editInputLevel2B) {
                if (itEditInputLevel2B == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "lastName")) {
                    customerRecord.setLastName(itEditInputLevel2B.getLastName());
                }
                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}

