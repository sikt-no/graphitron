package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperAddressJavaRecord;

public class AddressJavaMapper {
    public static List<MapperAddressJavaRecord> toJavaRecord(List<Address> address, String path,
                                                             RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var mapperAddressJavaRecordList = new ArrayList<MapperAddressJavaRecord>();

        if (address != null) {
            for (var itAddress : address) {
                if (itAddress == null) continue;
                var mapperAddressJavaRecord = new MapperAddressJavaRecord();
                var inner1 = itAddress.getInner1();
                if (inner1 != null && arguments.contains(pathHere + "inner1")) {
                    if (arguments.contains(pathHere + "inner1/code")) {
                        mapperAddressJavaRecord.setPostalCode(inner1.getCode());
                    }

                }

                var inner2 = itAddress.getInner2();
                if (inner2 != null && arguments.contains(pathHere + "inner2")) {
                    if (arguments.contains(pathHere + "inner2/code")) {
                        mapperAddressJavaRecord.setPostalCode(inner2.getCode());
                    }

                }

                mapperAddressJavaRecordList.add(mapperAddressJavaRecord);
            }
        }

        return mapperAddressJavaRecordList;
    }
}
