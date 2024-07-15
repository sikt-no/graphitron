package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditResponse0;
import java.lang.String;

public class EditResponse0TypeMapper {
    public static EditResponse0 recordToGraphType(String editResponse0Record, String path,
                                                  RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editResponse0 = new EditResponse0();

        if (select.contains(pathHere + "id0")) {
            editResponse0.setId0(editResponse0Record);
        }

        return editResponse0;
    }
}