package fake.code.generated.mappers;

import fake.graphql.example.model.DeleteInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class DeleteInputMapper {
    public static List<CustomerRecord> toJOOQRecord(List<DeleteInput> deleteInput, String path,
                                                Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var deleteInputRecordList = new ArrayList<CustomerRecord>();

        if (deleteInput != null) {
            for (var itDeleteInput : deleteInput) {
                if (itDeleteInput == null) continue;
                var deleteInputRecord = new CustomerRecord();
                deleteInputRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "email")) {
                    deleteInputRecord.setEmail(itDeleteInput.getEmail());
                }
                if (arguments.contains(pathHere + "id")) {
                    deleteInputRecord.setId(itDeleteInput.getId());
                }
                if (arguments.contains(pathHere + "firstName")) {
                    deleteInputRecord.setFirstName(itDeleteInput.getFirstName());
                }
                deleteInputRecordList.add(deleteInputRecord);
            }
        }

        return deleteInputRecordList;
    }
}
