package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.codereferences.records.MapperNestedJavaRecord;

public class AddressJavaMapper {
    public static List<MapperNestedJavaRecord> toJavaRecord(List<Address> address, String _iv_path,
                                                            RecordTransformer _iv_transform) {
        var _iv_pathHere = _iv_path.isEmpty() ? _iv_path : _iv_path + "/";
        var _iv_args = _iv_transform.getArguments();
        var mapperNestedJavaRecordList = new ArrayList<MapperNestedJavaRecord>();

        if (address != null) {
            for (var itAddress : address) {
                if (itAddress == null) continue;
                var mapperNestedJavaRecord = new MapperNestedJavaRecord();
                var dummyRecord = itAddress.getDummyRecord();
                if (dummyRecord != null && _iv_args.contains(_iv_pathHere + "dummyRecord")) {
                    mapperNestedJavaRecord.setDummyRecord(_iv_transform.dummyInputRecordToJavaRecord(dummyRecord, _iv_pathHere + "dummyRecord"));
                }

                mapperNestedJavaRecordList.add(mapperNestedJavaRecord);
            }
        }

        return mapperNestedJavaRecordList;
    }
}
