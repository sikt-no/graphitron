package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditResponseLevel2A;
import fake.graphql.example.model.EditResponseLevel3A;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerInnerRecord;

public class EditResponseLevel2ATypeMapper {
    public static List<EditResponseLevel2A> toGraphType(
            List<TestCustomerInnerRecord> testCustomerInnerRecord, String path,
            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editResponseLevel2AList = new ArrayList<EditResponseLevel2A>();

        if (testCustomerInnerRecord != null) {
            for (var itTestCustomerInnerRecord : testCustomerInnerRecord) {
                if (itTestCustomerInnerRecord == null) continue;
                var editResponseLevel2A = new EditResponseLevel2A();
                if (select.contains(pathHere + "hiddenValue")) {
                    var hiddenValue = new EditResponseLevel3A();
                    if (select.contains(pathHere + "hiddenValue/i")) {
                        hiddenValue.setI(itTestCustomerInnerRecord.getSomeInt());
                    }

                    editResponseLevel2A.setHiddenValue(hiddenValue);
                }

                var someRecord = itTestCustomerInnerRecord.getSomeRecord();
                if (someRecord != null && select.contains(pathHere + "edit3")) {
                    editResponseLevel2A.setEdit3(transform.editResponseLevel3BRecordToGraphType(someRecord, pathHere + "edit3"));
                }

                var someRecordList = itTestCustomerInnerRecord.getSomeRecordList();
                if (someRecordList != null && select.contains(pathHere + "edit3List")) {
                    editResponseLevel2A.setEdit3List(transform.editResponseLevel3BRecordToGraphType(someRecordList, pathHere + "edit3List"));
                }

                editResponseLevel2AList.add(editResponseLevel2A);
            }
        }

        return editResponseLevel2AList;
    }
}
