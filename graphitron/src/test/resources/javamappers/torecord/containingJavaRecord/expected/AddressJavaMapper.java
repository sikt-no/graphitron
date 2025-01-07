package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.codereferences.records.MapperNestedJavaRecord;

public class AddressJavaMapper {
    public static List<MapperNestedJavaRecord> toJavaRecord(List<Address> address, String path,
                                                            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var _args = transform.getArguments();
        var mapperNestedJavaRecordList = new ArrayList<MapperNestedJavaRecord>();

        if (address != null) {
            for (var itAddress : address) {
                if (itAddress == null) continue;
                var mapperNestedJavaRecord = new MapperNestedJavaRecord();
                var dummyRecord = itAddress.getDummyRecord();
                if (dummyRecord != null && _args.contains(pathHere + "dummyRecord")) {
                    mapperNestedJavaRecord.setDummyRecord(transform.dummyInputRecordToJavaRecord(dummyRecord, pathHere + "dummyRecord"));
                }

                mapperNestedJavaRecordList.add(mapperNestedJavaRecord);
            }
        }

        return mapperNestedJavaRecordList;
    }
}
