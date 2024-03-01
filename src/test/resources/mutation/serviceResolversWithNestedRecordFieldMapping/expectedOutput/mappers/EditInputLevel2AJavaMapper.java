package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInputLevel2A;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerInnerRecord;

public class EditInputLevel2AJavaMapper {
    public static List<TestCustomerInnerRecord> toJavaRecord(List<EditInputLevel2A> editInputLevel2A,
                                                             String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var testCustomerInnerRecordList = new ArrayList<TestCustomerInnerRecord>();

        if (editInputLevel2A != null) {
            for (var itEditInputLevel2A : editInputLevel2A) {
                if (itEditInputLevel2A == null) continue;
                var testCustomerInnerRecord = new TestCustomerInnerRecord();
                var hiddenValue = itEditInputLevel2A.getHiddenValue();
                if (hiddenValue != null && arguments.contains(pathHere + "hiddenValue")) {
                    if (arguments.contains(pathHere + "hiddenValue/i")) {
                        testCustomerInnerRecord.setSomeInt(hiddenValue.getI());
                    }
                }
                testCustomerInnerRecordList.add(testCustomerInnerRecord);
            }
        }

        return testCustomerInnerRecordList;
    }
}
