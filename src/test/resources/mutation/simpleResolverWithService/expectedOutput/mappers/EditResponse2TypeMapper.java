package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditResponse2;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;

public class EditResponse2TypeMapper {
    public static List<EditResponse2> toGraphType(List<TestCustomerRecord> testCustomerRecord,
                                                  String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editResponse2List = new ArrayList<EditResponse2>();

        if (testCustomerRecord != null) {
            for (var itTestCustomerRecord : testCustomerRecord) {
                if (itTestCustomerRecord == null) continue;
                var editResponse2 = new EditResponse2();
                if (select.contains(pathHere + "id2")) {
                    editResponse2.setId2(itTestCustomerRecord.getSomeID());
                }

                var recordList = itTestCustomerRecord.getRecordList();
                if (recordList != null && select.contains(pathHere + "customers")) {
                    editResponse2.setCustomers(transform.customerRecordToGraphType(recordList, pathHere + "customers"));
                }

                editResponse2List.add(editResponse2);
            }
        }

        return editResponse2List;
    }
}
