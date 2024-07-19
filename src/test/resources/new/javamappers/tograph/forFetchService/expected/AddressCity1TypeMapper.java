package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.AddressCity1;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperCityJavaRecord;

public class AddressCity1TypeMapper {
    public static List<AddressCity1> toGraphType(List<MapperCityJavaRecord> mapperCityJavaRecord,
                                                 String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var addressCity1List = new ArrayList<AddressCity1>();

        if (mapperCityJavaRecord != null) {
            for (var itMapperCityJavaRecord : mapperCityJavaRecord) {
                if (itMapperCityJavaRecord == null) continue;
                var addressCity1 = new AddressCity1();
                if (select.contains(pathHere + "city")) {
                    addressCity1.setCity(itMapperCityJavaRecord.getCityName());
                }

                addressCity1List.add(addressCity1);
            }
        }

        return addressCity1List;
    }
}
