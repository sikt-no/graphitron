package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.AddressCity1;
import fake.graphql.example.model.InnerWrapper;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.AddressRecord;

public class AddressTypeMapper {
    public static List<Address> recordToGraphType(List<AddressRecord> addressRecord, String path,
                                                  RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var addressList = new ArrayList<Address>();

        if (addressRecord != null) {
            for (var itAddressRecord : addressRecord) {
                if (itAddressRecord == null) continue;
                var address = new Address();
                if (select.contains(pathHere + "id")) {
                    address.setId(itAddressRecord.getId());
                }

                if (select.contains(pathHere + "postalCode")) {
                    address.setPostalCode(itAddressRecord.getPostalCode());
                }

                if (select.contains(pathHere + "inner")) {
                    var address_inner = new InnerWrapper();
                    if (select.contains(pathHere + "inner/postalCode")) {
                        address_inner.setPostalCode(itAddressRecord.getPostalCode());
                    }

                    address.setInner(address_inner);
                }

                if (select.contains(pathHere + "cityRecord")) {
                    var address_cityRecord = new AddressCity1();
                    if (select.contains(pathHere + "cityRecord/city")) {
                        address_cityRecord.setCity(itAddressRecord.getCity());
                    }

                    address.setCityRecord(address_cityRecord);
                }

                addressList.add(address);
            }
        }

        return addressList;
    }
}
