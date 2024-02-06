package fake.code.generated.transform;

import fake.code.generated.mappers.EditInputLevel1Mapper;
import fake.code.generated.mappers.EditInputLevel2AMapper;
import fake.code.generated.mappers.EditInputLevel2BMapper;
import fake.code.generated.mappers.EditInputLevel3Mapper;
import fake.code.generated.mappers.EditInputLevel4Mapper;
import fake.graphql.example.model.EditInputLevel1;
import fake.graphql.example.model.EditInputLevel2A;
import fake.graphql.example.model.EditInputLevel2B;
import fake.graphql.example.model.EditInputLevel3;
import fake.graphql.example.model.EditInputLevel4;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.CustomerRecord;
import org.jooq.DSLContext;

public class InputTransformer {
    private final DSLContext ctx;

    private final DataFetchingEnvironment env;

    private final Set<String> arguments;

    private final HashSet<GraphQLError> validationErrors = new HashSet<GraphQLError>();

    public InputTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        this.env = env;
        this.ctx = ctx;
        arguments = Arguments.flattenArgumentKeys(env.getArguments());
    }

    public List<CustomerRecord> editInputLevel1ToJOOQRecord(List<EditInputLevel1> input, String path,
                                                        String indexPath) {
        var records = EditInputLevel1Mapper.toJOOQRecord(input, path, arguments, ctx);
        validationErrors.addAll(EditInputLevel1Mapper.validate(records, indexPath, arguments, env));
        return records;
    }

    public List<CustomerRecord> editInputLevel3ToJOOQRecord(List<EditInputLevel3> input, String path,
                                                        String indexPath) {
        var records = EditInputLevel3Mapper.toJOOQRecord(input, path, arguments, ctx);
        validationErrors.addAll(EditInputLevel3Mapper.validate(records, indexPath, arguments, env));
        return records;
    }

    public List<CustomerRecord> editInputLevel4ToJOOQRecord(List<EditInputLevel4> input, String path,
                                                        String indexPath) {
        var records = EditInputLevel4Mapper.toJOOQRecord(input, path, arguments, ctx);
        validationErrors.addAll(EditInputLevel4Mapper.validate(records, indexPath, arguments, env));
        return records;
    }

    public List<CustomerRecord> editInputLevel2BToJOOQRecord(List<EditInputLevel2B> input, String path,
                                                         String indexPath) {
        var records = EditInputLevel2BMapper.toJOOQRecord(input, path, arguments, ctx);
        validationErrors.addAll(EditInputLevel2BMapper.validate(records, indexPath, arguments, env));
        return records;
    }

    public List<CustomerRecord> editInputLevel2AToJOOQRecord(List<EditInputLevel2A> input, String path,
                                                         String indexPath) {
        var records = EditInputLevel2AMapper.toJOOQRecord(input, path, arguments, ctx);
        validationErrors.addAll(EditInputLevel2AMapper.validate(records, indexPath, arguments, env));
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

    public CustomerRecord editInputLevel2BToJOOQRecord(EditInputLevel2B input, String path,
                                                   String indexPath) {
        return editInputLevel2BToJOOQRecord(List.of(input), path, indexPath).stream().findFirst().orElse(new CustomerRecord());
    }

    public CustomerRecord editInputLevel2AToJOOQRecord(EditInputLevel2A input, String path,
                                                   String indexPath) {
        return editInputLevel2AToJOOQRecord(List.of(input), path, indexPath).stream().findFirst().orElse(new CustomerRecord());
    }

    public void validate() {
        if (!validationErrors.isEmpty()) {
            throw new ValidationViolationGraphQLException(validationErrors);
        }
    }
}
