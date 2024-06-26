package fake.code.generated.mappers;

import fake.code.generated.queries.query.PaymentDBQueries;
import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditCustomerResponse4;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class EditCustomerResponse4TypeMapper {
    public static List<EditCustomerResponse4> toGraphType(
            List<no.fellesstudentsystem.graphitron.records.EditCustomerResponse4> editCustomerResponse4,
            String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editCustomerResponse4List = new ArrayList<EditCustomerResponse4>();

        if (editCustomerResponse4 != null) {
            for (var itEditCustomerResponse4 : editCustomerResponse4) {
                if (itEditCustomerResponse4 == null) continue;
                var editCustomerResponse4 = new EditCustomerResponse4();
                if (select.contains(pathHere + "id")) {
                    editCustomerResponse4.setId(itEditCustomerResponse4.getId4());
                }

                var payment4 = itEditCustomerResponse4.getPayment4();
                if (payment4 != null && select.contains(pathHere + "payment")) {
                    editCustomerResponse4.setPayment(PaymentDBQueries.loadPaymentByIdsAsNode(ctx, Set.of(payment4.getId()), select.withPrefix(pathHere + "payment")).values().stream().findFirst().orElse(null));
                }

                editCustomerResponse4List.add(editCustomerResponse4);
            }
        }

        return editCustomerResponse4List;
    }
}
