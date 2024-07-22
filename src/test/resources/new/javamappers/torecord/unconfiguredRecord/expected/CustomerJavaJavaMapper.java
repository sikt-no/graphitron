package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.CustomerJava;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.CustomerJavaRecord;

public class CustomerJavaJavaMapper {
    public static List<CustomerJavaRecord> toJavaRecord(List<CustomerJava> customerJava,
                                                        String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var customerJavaRecordList = new ArrayList<CustomerJavaRecord>();


        return customerJavaRecordList;
    }
}
