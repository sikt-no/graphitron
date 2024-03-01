package fake.code.generated.transform;

import fake.code.generated.mappers.EditInputLevel1JavaMapper;
import fake.code.generated.mappers.FilmTypeMapper;
import fake.graphql.example.model.EditInputLevel1;
import fake.graphql.example.model.Film;
import graphql.GraphQLError;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import no.fellesstudentsystem.graphitron.records.TestFilmRecord;
import no.fellesstudentsystem.graphql.exception.ValidationViolationGraphQLException;
import no.fellesstudentsystem.graphql.helpers.arguments.Arguments;
import no.fellesstudentsystem.graphql.helpers.selection.SelectionSet;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;
import org.jooq.DSLContext;

public class RecordTransformer {
    private final DSLContext ctx;

    private final DataFetchingEnvironment env;

    private final Set<String> arguments;

    private final SelectionSet select;

    private final HashSet<GraphQLError> validationErrors = new HashSet<GraphQLError>();

    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        this.env = env;
        this.ctx = ctx;
        select = new SelectionSet(env.getSelectionSet());
        arguments = Arguments.flattenArgumentKeys(env.getArguments());
    }

    public List<TestFilmRecord> editInputLevel1ToJavaRecord(List<EditInputLevel1> input,
                                                            String path) {
        return EditInputLevel1JavaMapper.toJavaRecord(input, path, this);
    }

    public List<Film> filmRecordToGraphType(List<FilmRecord> input, String path) {
        return FilmTypeMapper.recordToGraphType(input, path, this);
    }

    public TestFilmRecord editInputLevel1ToJavaRecord(EditInputLevel1 input, String path) {
        return editInputLevel1ToJavaRecord(List.of(input), path).stream().findFirst().orElse(new TestFilmRecord());
    }

    public Film filmRecordToGraphType(FilmRecord input, String path) {
        return filmRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public void validate() {
        if (!validationErrors.isEmpty()) {
            throw new ValidationViolationGraphQLException(validationErrors);
        }
    }

    public DSLContext getCtx() {
        return ctx;
    }

    public DataFetchingEnvironment getEnv() {
        return env;
    }

    public SelectionSet getSelect() {
        return select;
    }

    public Set<String> getArguments() {
        return arguments;
    }
}
