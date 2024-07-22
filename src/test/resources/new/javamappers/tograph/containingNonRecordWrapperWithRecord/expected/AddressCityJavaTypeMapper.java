package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.AddressCityJava;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperCityJavaRecord;

public class AddressCityJavaTypeMapper {
    public static List<AddressCityJava> toGraphType(List<MapperCityJavaRecord> mapperCityJavaRecord,
                                                    String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var addressCityJavaList = new ArrayList<AddressCityJava>();

        if (mapperCityJavaRecord != null) {
            for (var itMapperCityJavaRecord : mapperCityJavaRecord) {
                if (itMapperCityJavaRecord == null) continue;
                var addressCityJava = new AddressCityJava();
                if (select.contains(pathHere + "cityName")) {
                    addressCityJava.setCityName(itMapperCityJavaRecord.getCityName());
                }

                addressCityJavaList.add(addressCityJava);
            }
        }

        return addressCityJavaList;
    }
}
