package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditAddressInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class EditAddressInputJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditAddressInput> editAddressInput,
                                                String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (editAddressInput != null) {
            for (var itEditAddressInput : editAddressInput) {
                if (itEditAddressInput == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "id")) {
                    customerRecord.setId(itEditAddressInput.getId());
                }
                if (arguments.contains(pathHere + "addressId")) {
                    customerRecord.setAddressId(itEditAddressInput.getAddressId());
                }
                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}
