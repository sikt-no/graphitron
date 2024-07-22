package fake.code.generated.mappers;

import fake.code.generated.queries.query.AddressCityJOOQDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Address;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
                    address.setCityJOOQ(AddressCityJOOQDBQueries.loadAddressCityJOOQByIdsAsNode(transform.getCtx(), Set.of(cityJOOQ.getId()), select.withPrefix(pathHere + "cityJOOQ")).values().stream().findFirst().orElse(null));
                }

                var cityJOOQList = itMapperAddressJavaRecord.getCityJOOQList();
                if (cityJOOQList != null && select.contains(pathHere + "cityJOOQList")) {
                    var loadAddressCityJOOQByIdsAsNode = AddressCityJOOQDBQueries.loadAddressCityJOOQByIdsAsNode(transform.getCtx(), cityJOOQList.stream().map(it -> it.getId()).collect(Collectors.toSet()), select.withPrefix(pathHere + "cityJOOQList"));
                    address.setCityJOOQList(cityJOOQList.stream().map(it -> loadAddressCityJOOQByIdsAsNode.get(it.getId())).collect(Collectors.toList()));
                }

                addressList.add(address);
            }
        }

        return addressList;
    }
}
