package fake.code.generated.mappers;

import fake.graphql.example.model.EditInputLevel4;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class EditInputLevel4Mapper {
    public static List<CustomerRecord> toJOOQRecord(List<EditInputLevel4> editInputLevel4, String path,
                                                Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var editInputLevel4RecordList = new ArrayList<CustomerRecord>();

        if (editInputLevel4 != null) {
            for (var itEditInputLevel4 : editInputLevel4) {
                if (itEditInputLevel4 == null) continue;
                var editInputLevel4Record = new CustomerRecord();
                editInputLevel4Record.attach(ctx.configuration());
                if (arguments.contains(pathHere + "lastName")) {
                    editInputLevel4Record.setLastName(itEditInputLevel4.getLastName());
                }
                editInputLevel4RecordList.add(editInputLevel4Record);
            }
        }

        return editInputLevel4RecordList;
    }
}
