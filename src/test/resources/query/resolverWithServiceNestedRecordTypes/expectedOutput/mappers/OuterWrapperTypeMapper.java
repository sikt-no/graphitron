package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.OuterWrapper;
import java.lang.String;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class OuterWrapperTypeMapper {
    public static OuterWrapper recordToGraphType(List<CustomerRecord> outerWrapperRecord, String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var outerWrapper = new OuterWrapper();

        if (select.contains(pathHere + "customers")) {
            outerWrapper.setCustomers(transform.customerRecordToGraphType(outerWrapperRecord, pathHere + "customers"));
        }

        return outerWrapper;
    }
}
