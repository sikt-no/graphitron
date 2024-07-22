package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerJava;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.CustomerJavaRecord;

public class CustomerJavaTypeMapper {
    public static List<CustomerJava> toGraphType(List<CustomerJavaRecord> customerJavaRecord,
                                                 String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var customerJavaList = new ArrayList<CustomerJava>();


        return customerJavaList;
    }
}
