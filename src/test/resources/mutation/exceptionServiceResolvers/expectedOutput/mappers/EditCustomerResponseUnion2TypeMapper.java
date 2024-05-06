package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditCustomerResponseUnion2;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse1;

public class EditCustomerResponseUnion2TypeMapper {
    public static List<EditCustomerResponseUnion2> toGraphType(
            List<EditCustomerResponse1> editCustomerResponse1, String path,
            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editCustomerResponseUnion2List = new ArrayList<EditCustomerResponseUnion2>();

        if (editCustomerResponse1 != null) {
            for (var itEditCustomerResponse1 : editCustomerResponse1) {
                if (itEditCustomerResponse1 == null) continue;
                var editCustomerResponseUnion2 = new EditCustomerResponseUnion2();
                if (select.contains(pathHere + "id")) {
                    editCustomerResponseUnion2.setId(itEditCustomerResponse1.getId());
                }

                var editResponse2 = itEditCustomerResponse1.getEditResponse2();
                if (editResponse2 != null && select.contains(pathHere + "editCustomerResponse2")) {
                    editCustomerResponseUnion2.setEditCustomerResponse2(transform.editCustomerResponse2ToGraphType(editResponse2, pathHere + "editCustomerResponse2"));
                }

                var editResponse3 = itEditCustomerResponse1.getEditResponse3();
                if (editResponse3 != null && select.contains(pathHere + "editCustomerResponse3")) {
                    editCustomerResponseUnion2.setEditCustomerResponse3(transform.editCustomerResponse3ToGraphType(editResponse3, pathHere + "editCustomerResponse3"));
                }

                editCustomerResponseUnion2List.add(editCustomerResponseUnion2);
            }
        }

        return editCustomerResponseUnion2List;
    }
}

