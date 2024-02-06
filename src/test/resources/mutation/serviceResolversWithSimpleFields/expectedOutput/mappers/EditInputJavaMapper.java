package fake.code.generated.mappers;

import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.model.EditInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphitron.records.TestCustomerInputRecord;

public class EditInputJavaMapper {
    public static List<TestCustomerInputRecord> toJavaRecord(List<EditInput> editInput, String path,
                                                             Set<String> arguments, InputTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var testCustomerInputRecordList = new ArrayList<TestCustomerInputRecord>();

        if (editInput != null) {
            for (var itEditInput : editInput) {
                if (itEditInput == null) continue;
                var testCustomerInputRecord = new TestCustomerInputRecord();
                var hiddenValue1 = itEditInput.getHiddenValue1();
                if (hiddenValue1 != null) {
                    if (arguments.contains(pathHere + "hiddenValue1/id")) {
                        testCustomerInputRecord.setSomeID(hiddenValue1.getId());
                    }
                }
                var hiddenValue2 = itEditInput.getHiddenValue2();
                if (hiddenValue2 != null) {
                    if (arguments.contains(pathHere + "hiddenValue2/id")) {
                        testCustomerInputRecord.setSomeID(hiddenValue2.getId());
                    }
                }
                if (arguments.contains(pathHere + "id")) {
                    testCustomerInputRecord.setSomeID(itEditInput.getId());
                }
                if (arguments.contains(pathHere + "idList")) {
                    testCustomerInputRecord.setSomeListID(itEditInput.getIdList());
                }
                testCustomerInputRecordList.add(testCustomerInputRecord);
            }
        }

        return testCustomerInputRecordList;
    }
}
