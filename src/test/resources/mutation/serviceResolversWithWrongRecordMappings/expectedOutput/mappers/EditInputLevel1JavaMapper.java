package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInputLevel1;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;

public class EditInputLevel1JavaMapper {
    public static List<TestCustomerRecord> toJavaRecord(List<EditInputLevel1> editInputLevel1,
                                                        String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var testCustomerRecordList = new ArrayList<TestCustomerRecord>();

        if (editInputLevel1 != null) {
            for (var itEditInputLevel1 : editInputLevel1) {
                if (itEditInputLevel1 == null) continue;
                var testCustomerRecord = new TestCustomerRecord();
                if (arguments.contains(pathHere + "id")) {
                    testCustomerRecord.setSomeID(itEditInputLevel1.getId());
                }
                testCustomerRecordList.add(testCustomerRecord);
            }
        }

        return testCustomerRecordList;
    }
}
