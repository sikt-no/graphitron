package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditResponse;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;

public class EditResponseTypeMapper {
    public static List<EditResponse> toGraphType(List<TestCustomerRecord> testCustomerRecord,
                                                 String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editResponseList = new ArrayList<EditResponse>();

        if (testCustomerRecord != null) {
            for (var itTestCustomerRecord : testCustomerRecord) {
                if (itTestCustomerRecord == null) continue;
                var editResponse = new EditResponse();
                var testCustomerInnerRecord = itTestCustomerRecord.getTestCustomerInnerRecord();
                if (testCustomerInnerRecord != null && select.contains(pathHere + "testCustomerInnerRecord")) {
                    editResponse.setTestCustomerInnerRecord(transform.editResultToGraphType(testCustomerInnerRecord, pathHere + "testCustomerInnerRecord"));
                }

                editResponseList.add(editResponse);
            }
        }

        return editResponseList;
    }
}
