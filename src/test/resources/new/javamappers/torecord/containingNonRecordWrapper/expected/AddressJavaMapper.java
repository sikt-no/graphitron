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
                var inner = itAddress.getInner();
                if (inner != null && arguments.contains(pathHere + "inner")) {
                    if (arguments.contains(pathHere + "inner/postalCode")) {
                        mapperAddressJavaRecord.setPostalCode(inner.getPostalCode());
                    }

                }

                mapperAddressJavaRecordList.add(mapperAddressJavaRecord);
            }
        }

        return mapperAddressJavaRecordList;
    }
}
