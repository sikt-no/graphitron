package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperNestedJavaRecord;

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
                var customer = itMapperNestedJavaRecord.getCustomer();
                if (customer != null && select.contains(pathHere + "customer")) {
                    address.setCustomer(transform.customerTableRecordToGraphType(customer, pathHere + "customer"));
                }

                addressList.add(address);
            }
        }

        return addressList;
    }
}
