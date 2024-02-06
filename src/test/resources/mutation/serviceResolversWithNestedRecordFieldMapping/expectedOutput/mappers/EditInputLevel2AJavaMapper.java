package fake.code.generated.mappers;

import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.model.EditInputLevel2A;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphitron.records.TestCustomerInnerInputRecord;

public class EditInputLevel2AJavaMapper {
    public static List<TestCustomerInnerInputRecord> toJavaRecord(List<EditInputLevel2A> editInputLevel2A,
                                                    String path, Set<String> arguments, InputTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var testCustomerInnerInputRecordList = new ArrayList<TestCustomerInnerInputRecord>();

        if (editInputLevel2A != null) {
            for (var itEditInputLevel2A : editInputLevel2A) {
                if (itEditInputLevel2A == null) continue;
                var testCustomerInnerInputRecord = new TestCustomerInnerInputRecord();
                var hiddenValue = itEditInputLevel2A.getHiddenValue();
                if (hiddenValue != null) {
                    if (arguments.contains(pathHere + "hiddenValue/i")) {
                        testCustomerInnerInputRecord.setSomeInt(hiddenValue.getI());
                    }
                }
                testCustomerInnerInputRecordList.add(testCustomerInnerInputRecord);
            }
        }

        return testCustomerInnerInputRecordList;
    }
}
