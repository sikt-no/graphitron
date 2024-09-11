package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.codereferences.records.MapperNestedJavaRecord;

public class AddressJavaMapper {
    public static List<MapperNestedJavaRecord> toJavaRecord(List<Address> address, String path,
                                                            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var mapperNestedJavaRecordList = new ArrayList<MapperNestedJavaRecord>();

        if (address != null) {
            for (var itAddress : address) {
                if (itAddress == null) continue;
                var mapperNestedJavaRecord = new MapperNestedJavaRecord();
                var customer = itAddress.getCustomer();
                if (customer != null && arguments.contains(pathHere + "customer")) {
                    mapperNestedJavaRecord.setCustomer(transform.customerInputTableToJOOQRecord(customer, pathHere + "customer"));
                }

                mapperNestedJavaRecordList.add(mapperNestedJavaRecord);
            }
        }

        return mapperNestedJavaRecordList;
    }
}
