package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditResult;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerInnerRecord;

public class EditResultTypeMapper {
    public static List<EditResult> toGraphType(
            List<TestCustomerInnerRecord> testCustomerInnerRecord, String path,
            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editResultList = new ArrayList<EditResult>();

        if (testCustomerInnerRecord != null) {
            for (var itTestCustomerInnerRecord : testCustomerInnerRecord) {
                if (itTestCustomerInnerRecord == null) continue;
                var editResult = new EditResult();
                if (select.contains(pathHere + "someInt")) {
                    editResult.setSomeInt(itTestCustomerInnerRecord.getSomeInt());
                }

                var someRecord = itTestCustomerInnerRecord.getSomeRecord();
                if (someRecord != null && select.contains(pathHere + "someRecord")) {
                    editResult.setSomeRecord(transform.customerRecordToGraphType(someRecord, pathHere + "someRecord"));
                }

                editResultList.add(editResult);
            }
        }

        return editResultList;
    }
}
