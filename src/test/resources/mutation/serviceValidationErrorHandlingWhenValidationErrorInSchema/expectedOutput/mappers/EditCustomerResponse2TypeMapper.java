package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditCustomerResponse2;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse1;

public class EditCustomerResponse2TypeMapper {
    public static List<EditCustomerResponse2> toGraphType(
            List<EditCustomerResponse1> editCustomerResponse1, String path,
            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editCustomerResponse2List = new ArrayList<EditCustomerResponse2>();

        if (editCustomerResponse1 != null) {
            for (var itEditCustomerResponse1 : editCustomerResponse1) {
                if (itEditCustomerResponse1 == null) continue;
                var editCustomerResponse2 = new EditCustomerResponse2();
                if (select.contains(pathHere + "id")) {
                    editCustomerResponse2.setId(itEditCustomerResponse1.getId());
                }

                editCustomerResponse2List.add(editCustomerResponse2);
            }
        }

        return editCustomerResponse2List;
    }
}