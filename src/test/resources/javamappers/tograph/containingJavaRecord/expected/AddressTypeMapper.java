package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.codereferences.records.MapperNestedJavaRecord;

public class AddressTypeMapper {
    public static List<Address> toGraphType(List<MapperNestedJavaRecord> mapperNestedJavaRecord,
                                            String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var addressList = new ArrayList<Address>();

        if (mapperNestedJavaRecord != null) {
            for (var itMapperNestedJavaRecord : mapperNestedJavaRecord) {
                if (itMapperNestedJavaRecord == null) continue;
                var address = new Address();
                var dummyRecord = itMapperNestedJavaRecord.getDummyRecord();
                if (dummyRecord != null && select.contains(pathHere + "dummyRecord")) {
                    address.setDummyRecord(transform.dummyTypeRecordToGraphType(dummyRecord, pathHere + "dummyRecord"));
                }

                addressList.add(address);
            }
        }

        return addressList;
    }
}
