package fake.code.generated.transform;

import fake.code.generated.mappers.FilmInput1Mapper;
import fake.code.generated.mappers.FilmInput2Mapper;
import fake.graphql.example.model.FilmInput1;
import fake.graphql.example.model.FilmInput2;
import graphql.schema.DataFetchingEnvironment;
import graphql.GraphQLError;
import java.lang.String;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;
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

    public List<FilmRecord> filmInput2ToJOOQRecord(List<FilmInput2> input, String path) {
        var records = FilmInput2Mapper.toJOOQRecord(input, path, arguments, ctx);
        return records;
    }

    public List<FilmRecord> filmInput1ToJOOQRecord(List<FilmInput1> input, String path) {
        var records = FilmInput1Mapper.toJOOQRecord(input, path, arguments, ctx);
        return records;
    }

    public FilmRecord filmInput2ToJOOQRecord(FilmInput2 input, String path) {
        return filmInput2ToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new FilmRecord());
    }

    public FilmRecord filmInput1ToJOOQRecord(FilmInput1 input, String path) {
        return filmInput1ToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new FilmRecord());
    }

    public void validate() {
        if (!validationErrors.isEmpty()) {
            throw new ValidationViolationGraphQLException(validationErrors);
        }
    }
}
