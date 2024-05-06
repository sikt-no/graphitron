package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditResponseLevel3B;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class EditResponseLevel3BTypeMapper {
    public static List<EditResponseLevel3B> recordToGraphType(List<CustomerRecord> customerRecord,
                                                              String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editResponseLevel3BList = new ArrayList<EditResponseLevel3B>();

        if (customerRecord != null) {
            for (var itCustomerRecord : customerRecord) {
                if (itCustomerRecord == null) continue;
                var editResponseLevel3B = new EditResponseLevel3B();
                if (select.contains(pathHere + "lastName")) {
                    editResponseLevel3B.setLastName(itCustomerRecord.getLastName());
                }

                editResponseLevel3BList.add(editResponseLevel3B);
            }
        }

        return editResponseLevel3BList;
    }
}
