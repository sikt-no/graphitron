package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditResponse1;
import java.lang.String;
import java.util.List;

public class EditResponse1TypeMapper {
    public static EditResponse1 recordToGraphType(List<String> editResponse1Record, String path,
                                                  RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editResponse1 = new EditResponse1();

        if (select.contains(pathHere + "id1")) {
            editResponse1.setId1(editResponse1Record);
        }

        return editResponse1;
    }
}
