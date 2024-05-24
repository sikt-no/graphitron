package fake.code.generated.transform;

import fake.code.generated.mappers.EditAddressInputJOOQMapper;
import fake.code.generated.mappers.EditAddressResponseTypeMapper;
import fake.graphql.example.model.EditAddressInput;
import fake.graphql.example.model.EditAddressResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.EditCustomerAddressResponse;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<EditAddressResponse> editAddressResponseToGraphType(
            List<EditCustomerAddressResponse> input, String path) {
        return EditAddressResponseTypeMapper.toGraphType(input, path, this);
    }

    public List<CustomerRecord> editAddressInputToJOOQRecord(List<EditAddressInput> input,
                                                             String path) {
        return EditAddressInputJOOQMapper.toJOOQRecord(input, path, this);
    }

    public EditAddressResponse editAddressResponseToGraphType(EditCustomerAddressResponse input,
                                                              String path) {
        return editAddressResponseToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public CustomerRecord editAddressInputToJOOQRecord(EditAddressInput input, String path) {
        return editAddressInputToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }
}
