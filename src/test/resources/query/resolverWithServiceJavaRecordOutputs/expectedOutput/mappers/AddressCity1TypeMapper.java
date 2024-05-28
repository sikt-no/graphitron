package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.AddressCity1;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestCityRecord;

public class AddressCity1TypeMapper {
    public static List<AddressCity1> toGraphType(List<TestCityRecord> testCityRecord, String path,
                                                 RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var addressCity1List = new ArrayList<AddressCity1>();

        if (testCityRecord != null) {
            for (var itTestCityRecord : testCityRecord) {
                if (itTestCityRecord == null) continue;
                var addressCity1 = new AddressCity1();
                if (select.contains(pathHere + "city")) {
                    addressCity1.setCity(itTestCityRecord.getCityName());
                }

                addressCity1List.add(addressCity1);
            }
        }

        return addressCity1List;
    }
}
