package fake.code.generated.transform;

import fake.code.generated.mappers.EditInputLevel1JavaMapper;
import fake.code.generated.mappers.FilmTypeMapper;
import fake.graphql.example.model.EditInputLevel1;
import fake.graphql.example.model.Film;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphitron.records.TestFilmRecord;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
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
}
