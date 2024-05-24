package fake.code.generated.transform;

import fake.code.generated.mappers.EditInputJOOQMapper;
import fake.code.generated.mappers.EditResponseTypeMapper;
import fake.graphql.example.model.EditInput;
import fake.graphql.example.model.EditResponse;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.EditCustomerResponse1;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<EditResponse> editResponseToGraphType(List<EditCustomerResponse1> input,
                                                      String path) {
        return EditResponseTypeMapper.toGraphType(input, path, this);
    }

    public List<CustomerRecord> editInputToJOOQRecord(List<EditInput> input, String path) {
        return EditInputJOOQMapper.toJOOQRecord(input, path, this);
    }

    public EditResponse editResponseToGraphType(EditCustomerResponse1 input, String path) {
        return editResponseToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public CustomerRecord editInputToJOOQRecord(EditInput input, String path) {
        return editInputToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new CustomerRecord());
    }
}
