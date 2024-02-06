package fake.code.generated.mappers;

import fake.code.generated.transform.InputTransformer;
import fake.graphql.example.model.EditInputLevel1;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphitron.records.TestCustomerInputRecord;

public class EditInputLevel1JavaMapper {
    public static List<TestCustomerInputRecord> toJavaRecord(List<EditInputLevel1> editInputLevel1,
                                                                  String path, Set<String> arguments, InputTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var testCustomerInputRecordList = new ArrayList<TestCustomerInputRecord>();

        if (editInputLevel1 != null) {
            for (var itEditInputLevel1 : editInputLevel1) {
                if (itEditInputLevel1 == null) continue;
                var testCustomerInputRecord = new TestCustomerInputRecord();
                if (arguments.contains(pathHere + "id")) {
                    testCustomerInputRecord.setSomeID(itEditInputLevel1.getId());
                }
                testCustomerInputRecordList.add(testCustomerInputRecord);
            }
        }

        return testCustomerInputRecordList;
    }
}
