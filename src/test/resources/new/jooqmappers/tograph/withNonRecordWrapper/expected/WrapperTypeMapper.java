package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Wrapper;
import java.lang.String;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class WrapperTypeMapper {
    public static Wrapper recordToGraphType(List<CustomerRecord> wrapperRecord, String path,
                                            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var wrapper = new Wrapper();

        if (select.contains(pathHere + "customer")) {
            wrapper.setCustomer(transform.customerRecordToGraphType(wrapperRecord, pathHere + "customer"));
        }

        return wrapper;
    }
}
