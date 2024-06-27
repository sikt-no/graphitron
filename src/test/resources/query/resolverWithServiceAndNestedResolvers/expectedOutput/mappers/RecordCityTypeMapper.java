package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.RecordCity;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestIDRecord;

public class RecordCityTypeMapper {
    public static List<RecordCity> toGraphType(List<TestIDRecord> testIDRecord, String path,
                                               RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var recordCityList = new ArrayList<RecordCity>();

        if (testIDRecord != null) {
            for (var itTestIDRecord : testIDRecord) {
                if (itTestIDRecord == null) continue;
                var recordCity = new RecordCity();
                if (select.contains(pathHere + "id")) {
                    recordCity.setId(itTestIDRecord.getId());
                }

                recordCityList.add(recordCity);
            }
        }

        return recordCityList;
    }
}
