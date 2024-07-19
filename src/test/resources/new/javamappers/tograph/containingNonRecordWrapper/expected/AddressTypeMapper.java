package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.InnerWrapper;
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
                if (select.contains(pathHere + "inner")) {
                    var inner = new InnerWrapper();
                    if (select.contains(pathHere + "inner/postalCode")) {
                        inner.setPostalCode(itMapperAddressJavaRecord.getPostalCode());
                    }

                    address.setInner(inner);
                }

                addressList.add(address);
            }
        }

        return addressList;
    }
}
