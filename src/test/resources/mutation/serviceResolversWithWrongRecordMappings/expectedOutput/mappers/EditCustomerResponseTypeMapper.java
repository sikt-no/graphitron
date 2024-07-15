package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditCustomerResponse;
import java.lang.String;
import java.util.List;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;

public class EditCustomerResponseTypeMapper {
    public static EditCustomerResponse recordToGraphType(
            List<CustomerRecord> editCustomerResponseRecord, String path,
            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editCustomerResponse = new EditCustomerResponse();

        if (select.contains(pathHere + "customer")) {
            editCustomerResponse.setCustomer(transform.customerRecordToGraphType(editCustomerResponseRecord, pathHere + "customer"));
        }

        return editCustomerResponse;
    }
}
