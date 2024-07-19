package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
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
                if (select.contains(pathHere + "inner")) {
                    var address_inner = new InnerWrapper();
                    if (select.contains(pathHere + "inner/postalCode")) {
                        address_inner.setPostalCode(itAddressRecord.getPostalCode());
                    }

                    address.setInner(address_inner);
                }

                addressList.add(address);
            }
        }

        return addressList;
    }
}
