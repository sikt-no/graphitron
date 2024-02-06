package fake.code.generated.mappers;

import fake.graphql.example.model.UpsertInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class UpsertInputMapper {
    public static List<CustomerRecord> toJOOQRecord(List<UpsertInput> upsertInput, String path,
                                                Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var upsertInputRecordList = new ArrayList<CustomerRecord>();

        if (upsertInput != null) {
            for (var itUpsertInput : upsertInput) {
                if (itUpsertInput == null) continue;
                var upsertInputRecord = new CustomerRecord();
                upsertInputRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "id")) {
                    upsertInputRecord.setId(itUpsertInput.getId());
                }
                if (arguments.contains(pathHere + "customerId")) {
                    upsertInputRecord.setCustomerId(itUpsertInput.getCustomerId());
                }
                if (arguments.contains(pathHere + "firstName")) {
                    upsertInputRecord.setFirstName(itUpsertInput.getFirstName());
                }
                if (arguments.contains(pathHere + "lastName")) {
                    upsertInputRecord.setLastName(itUpsertInput.getLastName());
                }
                if (arguments.contains(pathHere + "storeId")) {
                    upsertInputRecord.setStoreId(itUpsertInput.getStoreId());
                }
                if (arguments.contains(pathHere + "addressId")) {
                    upsertInputRecord.setAddressId(itUpsertInput.getAddressId());
                }
                if (arguments.contains(pathHere + "active")) {
                    upsertInputRecord.setActivebool(itUpsertInput.getActive());
                }
                if (arguments.contains(pathHere + "createdDate")) {
                    upsertInputRecord.setCreateDate(itUpsertInput.getCreatedDate());
                }
                upsertInputRecordList.add(upsertInputRecord);
            }
        }

        return upsertInputRecordList;
    }
}
