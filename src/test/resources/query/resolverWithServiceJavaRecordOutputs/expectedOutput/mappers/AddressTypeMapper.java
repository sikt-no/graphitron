package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.InnerWrapper;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestAddressRecord;

public class AddressTypeMapper {
    public static List<Address> toGraphType(List<TestAddressRecord> testAddressRecord, String path,
                                            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var addressList = new ArrayList<Address>();

        if (testAddressRecord != null) {
            for (var itTestAddressRecord : testAddressRecord) {
                if (itTestAddressRecord == null) continue;
                var address = new Address();
                if (select.contains(pathHere + "id")) {
                    address.setId(itTestAddressRecord.getId());
                }

                if (select.contains(pathHere + "inner")) {
                    var inner = new InnerWrapper();
                    if (select.contains(pathHere + "inner/postalCode")) {
                        inner.setPostalCode(itTestAddressRecord.getPostalCode());
                    }

                    address.setInner(inner);
                }

                var city = itTestAddressRecord.getCity();
                if (city != null && select.contains(pathHere + "city")) {
                    address.setCity(transform.addressCity0RecordToGraphType(city, pathHere + "city"));
                }

                var cityRecord = itTestAddressRecord.getCityRecord();
                if (cityRecord != null && select.contains(pathHere + "cityRecord")) {
                    address.setCityRecord(transform.addressCity1ToGraphType(cityRecord, pathHere + "cityRecord"));
                }

                addressList.add(address);
            }
        }

        return addressList;
    }
}
