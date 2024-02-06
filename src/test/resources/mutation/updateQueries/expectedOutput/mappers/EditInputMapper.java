package fake.code.generated.mappers;

import fake.graphql.example.model.EditInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditInputMapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInput> editInput, String path,
                                                Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInputRecordList = new ArrayList<CustomerRecord>();

        if (editInput != null) {
            for (var itEditInput : editInput) {
                if (itEditInput == null) continue;
                var editInputRecord = new CustomerRecord();
                editInputRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "email")) {
                    editInputRecord.setEmail(itEditInput.getEmail());
                }
                if (arguments.contains(pathHere + "id")) {
                    editInputRecord.setId(itEditInput.getId());
                }
                if (arguments.contains(pathHere + "firstName")) {
                    editInputRecord.setFirstName(itEditInput.getFirstName());
                }
                editInputRecordList.add(editInputRecord);
            }
        }

        return editInputRecordList;
    }
}
