package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.OuterWrapper;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCustomerRecord;

public class OuterWrapperTypeMapper {
    public static List<OuterWrapper> toGraphType(List<TestCustomerRecord> testCustomerRecord,
                                                 String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var outerWrapperList = new ArrayList<OuterWrapper>();

        if (testCustomerRecord != null) {
            for (var itTestCustomerRecord : testCustomerRecord) {
                if (itTestCustomerRecord == null) continue;
                var outerWrapper = new OuterWrapper();
                var record = itTestCustomerRecord.getRecord();
                if (record != null && select.contains(pathHere + "customer")) {
                    outerWrapper.setCustomer(transform.customerRecordToGraphType(record, pathHere + "customer"));
                }

                outerWrapperList.add(outerWrapper);
            }
        }

        return outerWrapperList;
    }
}
