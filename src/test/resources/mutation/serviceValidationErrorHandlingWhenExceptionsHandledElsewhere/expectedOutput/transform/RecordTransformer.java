package fake.code.generated.transform;

import fake.code.generated.mappers.EditCustomerResponse2TypeMapper;
import fake.code.generated.mappers.EditCustomerResponseTypeMapper;
import fake.code.generated.mappers.EditInputJOOQMapper;
import fake.graphql.example.model.EditCustomerResponse;
import fake.graphql.example.model.EditCustomerResponse2;
import fake.graphql.example.model.EditInput;
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

    public List<EditCustomerResponse> editCustomerResponseToGraphType(
            List<EditCustomerResponse1> input, String path) {
        return EditCustomerResponseTypeMapper.toGraphType(input, path, this);
    }

    public List<EditCustomerResponse2> editCustomerResponse2ToGraphType(
            List<EditCustomerResponse1> input, String path) {
        return EditCustomerResponse2TypeMapper.toGraphType(input, path, this);
    }

    public List<CustomerRecord> editInputToJOOQRecord(List<EditInput> input, String path,
                                                      String indexPath) {
        var records = EditInputJOOQMapper.toJOOQRecord(input, path, this);
        validationErrors.addAll(EditInputJOOQMapper.validate(records, indexPath, this));
        return records;
    }

    public EditCustomerResponse editCustomerResponseToGraphType(EditCustomerResponse1 input,
                                                                String path) {
        return editCustomerResponseToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public EditCustomerResponse2 editCustomerResponse2ToGraphType(EditCustomerResponse1 input,
                                                                  String path) {
        return editCustomerResponse2ToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public CustomerRecord editInputToJOOQRecord(EditInput input, String path, String indexPath) {
        return editInputToJOOQRecord(List.of(input), path, indexPath).stream().findFirst().orElse(new CustomerRecord());
    }
}