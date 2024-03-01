package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.InsertInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class InsertInputJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<InsertInput> insertInput, String path,
                                                RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (insertInput != null) {
            for (var itInsertInput : insertInput) {
                if (itInsertInput == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "id")) {
                    customerRecord.setId(itInsertInput.getId());
                }
                if (arguments.contains(pathHere + "customerId")) {
                    customerRecord.setCustomerId(itInsertInput.getCustomerId());
                }
                if (arguments.contains(pathHere + "firstName")) {
                    customerRecord.setFirstName(itInsertInput.getFirstName());
                }
                if (arguments.contains(pathHere + "lastName")) {
                    customerRecord.setLastName(itInsertInput.getLastName());
                }
                if (arguments.contains(pathHere + "storeId")) {
                    customerRecord.setStoreId(itInsertInput.getStoreId());
                }
                if (arguments.contains(pathHere + "addressId")) {
                    customerRecord.setAddressId(itInsertInput.getAddressId());
                }
                if (arguments.contains(pathHere + "active")) {
                    customerRecord.setActivebool(itInsertInput.getActive());
                }
                if (arguments.contains(pathHere + "createdDate")) {
                    customerRecord.setCreateDate(itInsertInput.getCreatedDate());
                }
                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}
