package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import fake.graphql.example.model.Wrapper1;
import fake.graphql.example.model.Wrapper2;
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
                if (select.contains(pathHere + "inner1")) {
                    var inner1 = new Wrapper1();
                    if (select.contains(pathHere + "inner1/code")) {
                        inner1.setCode(itMapperAddressJavaRecord.getPostalCode());
                    }

                    address.setInner1(inner1);
                }

                var postalCode = itMapperAddressJavaRecord.getPostalCode();
                if (postalCode != null && select.contains(pathHere + "inner2")) {
                    var inner2 = new Wrapper2();
                    if (select.contains(pathHere + "inner2/code")) {
                        inner2.setCode(itMapperAddressJavaRecord.getPostalCode());
                    }

                    address.setInner2(inner2);
                }

                addressList.add(address);
            }
        }

        return addressList;
    }
}
