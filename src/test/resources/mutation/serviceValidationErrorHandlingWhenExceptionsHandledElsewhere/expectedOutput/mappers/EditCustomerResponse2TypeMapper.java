package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditCustomerResponse2;
import java.lang.String;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse1;

public class EditCustomerResponse2TypeMapper {
    public static EditCustomerResponse2 recordToGraphType(
            EditCustomerResponse1 editCustomerResponse2Record, String path,
            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editCustomerResponse2 = new EditCustomerResponse2();

        if (select.contains(pathHere + "id")) {
            editCustomerResponse2.setId(editCustomerResponse2Record);
        }

        return editCustomerResponse2;
    }
}
