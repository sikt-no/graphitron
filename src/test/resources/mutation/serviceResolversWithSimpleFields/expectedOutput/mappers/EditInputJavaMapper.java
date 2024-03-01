package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;

public class EditInputJavaMapper {
    public static List<TestCustomerRecord> toJavaRecord(List<EditInput> editInput, String path,
                                                        RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var testCustomerRecordList = new ArrayList<TestCustomerRecord>();

        if (editInput != null) {
            for (var itEditInput : editInput) {
                if (itEditInput == null) continue;
                var testCustomerRecord = new TestCustomerRecord();
                if (arguments.contains(pathHere + "id")) {
                    testCustomerRecord.setSomeID(itEditInput.getId());
                }
                if (arguments.contains(pathHere + "idList")) {
                    testCustomerRecord.setSomeListID(itEditInput.getIdList());
                }
                var hiddenValue1 = itEditInput.getHiddenValue1();
                if (hiddenValue1 != null && arguments.contains(pathHere + "hiddenValue1")) {
                    if (arguments.contains(pathHere + "hiddenValue1/id")) {
                        testCustomerRecord.setSomeID(hiddenValue1.getId());
                    }
                }
                var hiddenValue2 = itEditInput.getHiddenValue2();
                if (hiddenValue2 != null && arguments.contains(pathHere + "hiddenValue2")) {
                    if (arguments.contains(pathHere + "hiddenValue2/id")) {
                        testCustomerRecord.setSomeID(hiddenValue2.getId());
                    }
                }
                testCustomerRecordList.add(testCustomerRecord);
            }
        }

        return testCustomerRecordList;
    }
}
