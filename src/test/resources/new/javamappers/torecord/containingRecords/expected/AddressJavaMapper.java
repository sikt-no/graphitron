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
                var cityJOOQ = itAddress.getCityJOOQ();
                if (cityJOOQ != null && arguments.contains(pathHere + "cityJOOQ")) {
                    mapperAddressJavaRecord.setCityJOOQ(transform.addressCityJOOQToJOOQRecord(cityJOOQ, pathHere + "cityJOOQ"));
                }

                var cityJava = itAddress.getCityJava();
                if (cityJava != null && arguments.contains(pathHere + "cityJava")) {
                    mapperAddressJavaRecord.setCityJava(transform.addressCityJavaToJavaRecord(cityJava, pathHere + "cityJava"));
                }

                mapperAddressJavaRecordList.add(mapperAddressJavaRecord);
            }
        }

        return mapperAddressJavaRecordList;
    }
}
