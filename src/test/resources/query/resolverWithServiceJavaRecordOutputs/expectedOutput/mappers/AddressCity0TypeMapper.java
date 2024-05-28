package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.AddressCity0;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CityRecord;

public class AddressCity0TypeMapper {
    public static List<AddressCity0> recordToGraphType(List<CityRecord> cityRecord, String path,
                                                       RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var addressCity0List = new ArrayList<AddressCity0>();

        if (cityRecord != null) {
            for (var itCityRecord : cityRecord) {
                if (itCityRecord == null) continue;
                var addressCity0 = new AddressCity0();
                if (select.contains(pathHere + "city")) {
                    addressCity0.setCity(itCityRecord.getCity());
                }

                addressCity0List.add(addressCity0);
            }
        }

        return addressCity0List;
    }
}
