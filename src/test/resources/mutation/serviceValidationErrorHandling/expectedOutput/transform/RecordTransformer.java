package fake.code.generated.transform;

import fake.code.generated.mappers.EditCustomerResponseTypeMapper;
import fake.code.generated.mappers.EditInputLevel1JOOQMapper;
import fake.code.generated.mappers.EditInputLevel2AJOOQMapper;
import fake.code.generated.mappers.EditInputLevel2BJOOQMapper;
import fake.code.generated.mappers.EditInputLevel3JOOQMapper;
import fake.code.generated.mappers.EditInputLevel4JOOQMapper;
import fake.graphql.example.model.EditCustomerResponse;
import fake.graphql.example.model.EditInputLevel1;
import fake.graphql.example.model.EditInputLevel2A;
import fake.graphql.example.model.EditInputLevel2B;
import fake.graphql.example.model.EditInputLevel3;
import fake.graphql.example.model.EditInputLevel4;
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

    public List<CustomerRecord> editInputLevel1ToJOOQRecord(List<EditInputLevel1> input,
                                                            String path, String indexPath) {
        var records = EditInputLevel1JOOQMapper.toJOOQRecord(input, path, this);
        validationErrors.addAll(EditInputLevel1JOOQMapper.validate(records, indexPath, this));
        return records;
    }

    public List<CustomerRecord> editInputLevel3ToJOOQRecord(List<EditInputLevel3> input,
                                                            String path, String indexPath) {
        var records = EditInputLevel3JOOQMapper.toJOOQRecord(input, path, this);
        validationErrors.addAll(EditInputLevel3JOOQMapper.validate(records, indexPath, this));
        return records;
    }

    public List<CustomerRecord> editInputLevel4ToJOOQRecord(List<EditInputLevel4> input,
                                                            String path, String indexPath) {
        var records = EditInputLevel4JOOQMapper.toJOOQRecord(input, path, this);
        validationErrors.addAll(EditInputLevel4JOOQMapper.validate(records, indexPath, this));
        return records;
    }

    public List<EditCustomerResponse> editCustomerResponseToGraphType(
            List<EditCustomerResponse1> input, String path) {
        return EditCustomerResponseTypeMapper.toGraphType(input, path, this);
    }

    public List<CustomerRecord> editInputLevel2BToJOOQRecord(List<EditInputLevel2B> input,
                                                             String path, String indexPath) {
        var records = EditInputLevel2BJOOQMapper.toJOOQRecord(input, path, this);
        validationErrors.addAll(EditInputLevel2BJOOQMapper.validate(records, indexPath, this));
        return records;
    }

    public List<CustomerRecord> editInputLevel2AToJOOQRecord(List<EditInputLevel2A> input,
                                                             String path, String indexPath) {
        var records = EditInputLevel2AJOOQMapper.toJOOQRecord(input, path, this);
        validationErrors.addAll(EditInputLevel2AJOOQMapper.validate(records, indexPath, this));
        return records;
    }

    public CustomerRecord editInputLevel1ToJOOQRecord(EditInputLevel1 input, String path,
                                                      String indexPath) {
        return editInputLevel1ToJOOQRecord(List.of(input), path, indexPath).stream().findFirst().orElse(new CustomerRecord());
    }

    public CustomerRecord editInputLevel3ToJOOQRecord(EditInputLevel3 input, String path,
                                                      String indexPath) {
        return editInputLevel3ToJOOQRecord(List.of(input), path, indexPath).stream().findFirst().orElse(new CustomerRecord());
    }

    public CustomerRecord editInputLevel4ToJOOQRecord(EditInputLevel4 input, String path,
                                                      String indexPath) {
        return editInputLevel4ToJOOQRecord(List.of(input), path, indexPath).stream().findFirst().orElse(new CustomerRecord());
    }

    public EditCustomerResponse editCustomerResponseToGraphType(EditCustomerResponse1 input,
                                                                String path) {
        return editCustomerResponseToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public CustomerRecord editInputLevel2BToJOOQRecord(EditInputLevel2B input, String path,
                                                       String indexPath) {
        return editInputLevel2BToJOOQRecord(List.of(input), path, indexPath).stream().findFirst().orElse(new CustomerRecord());
    }

    public CustomerRecord editInputLevel2AToJOOQRecord(EditInputLevel2A input, String path,
                                                       String indexPath) {
        return editInputLevel2AToJOOQRecord(List.of(input), path, indexPath).stream().findFirst().orElse(new CustomerRecord());
    }
}
