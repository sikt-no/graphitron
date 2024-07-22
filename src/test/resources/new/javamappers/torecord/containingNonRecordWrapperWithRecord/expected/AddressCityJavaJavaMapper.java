package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.AddressCityJava;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperCityJavaRecord;

public class AddressCityJavaJavaMapper {
    public static List<MapperCityJavaRecord> toJavaRecord(List<AddressCityJava> addressCityJava,
                                                          String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var mapperCityJavaRecordList = new ArrayList<MapperCityJavaRecord>();

        if (addressCityJava != null) {
            for (var itAddressCityJava : addressCityJava) {
                if (itAddressCityJava == null) continue;
                var mapperCityJavaRecord = new MapperCityJavaRecord();
                if (arguments.contains(pathHere + "cityName")) {
                    mapperCityJavaRecord.setCityName(itAddressCityJava.getCityName());
                }

                mapperCityJavaRecordList.add(mapperCityJavaRecord);
            }
        }

        return mapperCityJavaRecordList;
    }
}
