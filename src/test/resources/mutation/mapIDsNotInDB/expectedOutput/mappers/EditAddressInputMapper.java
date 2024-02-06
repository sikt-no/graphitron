package fake.code.generated.mappers;

import fake.graphql.example.model.EditAddressInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditAddressInputMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditAddressInput> editAddressInput,
                                                String path, Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editAddressInputRecordList = new ArrayList<CustomerRecord>();

        if (editAddressInput != null) {
            for (var itEditAddressInput : editAddressInput) {
                if (itEditAddressInput == null) continue;
                var editAddressInputRecord = new CustomerRecord();
                editAddressInputRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "id")) {
                    editAddressInputRecord.setId(itEditAddressInput.getId());
                }
                if (arguments.contains(pathHere + "addressId")) {
                    editAddressInputRecord.setAddressId(itEditAddressInput.getAddressId());
                }
                editAddressInputRecordList.add(editAddressInputRecord);
            }
        }

        return editAddressInputRecordList;
    }
}
