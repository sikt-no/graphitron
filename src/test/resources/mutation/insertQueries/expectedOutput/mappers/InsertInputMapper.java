package fake.code.generated.mappers;

import fake.graphql.example.model.InsertInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class InsertInputMapper {
    public static List<CustomerRecord> toJOOQRecord(List<InsertInput> insertInput, String path,
                                                Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var insertInputRecordList = new ArrayList<CustomerRecord>();

        if (insertInput != null) {
            for (var itInsertInput : insertInput) {
                if (itInsertInput == null) continue;
                var insertInputRecord = new CustomerRecord();
                insertInputRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "id")) {
                    insertInputRecord.setId(itInsertInput.getId());
                }
                if (arguments.contains(pathHere + "customerId")) {
                    insertInputRecord.setCustomerId(itInsertInput.getCustomerId());
                }
                if (arguments.contains(pathHere + "firstName")) {
                    insertInputRecord.setFirstName(itInsertInput.getFirstName());
                }
                if (arguments.contains(pathHere + "lastName")) {
                    insertInputRecord.setLastName(itInsertInput.getLastName());
                }
                if (arguments.contains(pathHere + "storeId")) {
                    insertInputRecord.setStoreId(itInsertInput.getStoreId());
                }
                if (arguments.contains(pathHere + "addressId")) {
                    insertInputRecord.setAddressId(itInsertInput.getAddressId());
                }
                if (arguments.contains(pathHere + "active")) {
                    insertInputRecord.setActivebool(itInsertInput.getActive());
                }
                if (arguments.contains(pathHere + "createdDate")) {
                    insertInputRecord.setCreateDate(itInsertInput.getCreatedDate());
                }
                insertInputRecordList.add(insertInputRecord);
            }
        }

        return insertInputRecordList;
    }
}
