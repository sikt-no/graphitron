package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.UpsertInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class UpsertInputJOOQMapper {
    public static List<CustomerRecord> toJOOQRecord(List<UpsertInput> upsertInput, String path,
                                                RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var customerRecordList = new ArrayList<CustomerRecord>();

        if (upsertInput != null) {
            for (var itUpsertInput : upsertInput) {
                if (itUpsertInput == null) continue;
                var customerRecord = new CustomerRecord();
                customerRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "id")) {
                    customerRecord.setId(itUpsertInput.getId());
                }
                if (arguments.contains(pathHere + "customerId")) {
                    customerRecord.setCustomerId(itUpsertInput.getCustomerId());
                }
                if (arguments.contains(pathHere + "firstName")) {
                    customerRecord.setFirstName(itUpsertInput.getFirstName());
                }
                if (arguments.contains(pathHere + "lastName")) {
                    customerRecord.setLastName(itUpsertInput.getLastName());
                }
                if (arguments.contains(pathHere + "storeId")) {
                    customerRecord.setStoreId(itUpsertInput.getStoreId());
                }
                if (arguments.contains(pathHere + "addressId")) {
                    customerRecord.setAddressId(itUpsertInput.getAddressId());
                }
                if (arguments.contains(pathHere + "active")) {
                    customerRecord.setActivebool(itUpsertInput.getActive());
                }
                if (arguments.contains(pathHere + "createdDate")) {
                    customerRecord.setCreateDate(itUpsertInput.getCreatedDate());
                }
                customerRecordList.add(customerRecord);
            }
        }

        return customerRecordList;
    }
}
