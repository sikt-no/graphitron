package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperAddressJavaRecord;

public class AddressTypeMapper {
    public static List<Address> toGraphType(List<MapperAddressJavaRecord> mapperAddressJavaRecord,
                                            String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var addressList = new ArrayList<Address>();

        if (mapperAddressJavaRecord != null) {
            for (var itMapperAddressJavaRecord : mapperAddressJavaRecord) {
                if (itMapperAddressJavaRecord == null) continue;
                var address = new Address();
                var cityJOOQ = itMapperAddressJavaRecord.getCityJOOQ();
                if (cityJOOQ != null && select.contains(pathHere + "cityJOOQ")) {
                    address.setCityJOOQ(transform.addressCityJOOQRecordToGraphType(cityJOOQ, pathHere + "cityJOOQ"));
                }

                var cityJava = itMapperAddressJavaRecord.getCityJava();
                if (cityJava != null && select.contains(pathHere + "cityJava")) {
                    address.setCityJava(transform.addressCityJavaToGraphType(cityJava, pathHere + "cityJava"));
                }

                addressList.add(address);
            }
        }

        return addressList;
    }
}
