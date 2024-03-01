package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInput2;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class EditInput2JOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInput2> editInput2, String path,
                                                RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (editInput2 != null) {
            for (var itEditInput2 : editInput2) {
                if (itEditInput2 == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "email")) {
                    customerRecord.setEmail(itEditInput2.getEmail());
                }
                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}
