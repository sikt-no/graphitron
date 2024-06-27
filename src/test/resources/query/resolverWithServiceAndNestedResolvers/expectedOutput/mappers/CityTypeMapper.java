package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.City;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CityRecord;

public class CityTypeMapper {
    public static List<City> recordToGraphType(List<CityRecord> cityRecord, String path,
                                               RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var cityList = new ArrayList<City>();

        if (cityRecord != null) {
            for (var itCityRecord : cityRecord) {
                if (itCityRecord == null) continue;
                var city = new City();
                if (select.contains(pathHere + "id")) {
                    city.setId(itCityRecord.getId());
                }

                cityList.add(city);
            }
        }

        return cityList;
    }
}
