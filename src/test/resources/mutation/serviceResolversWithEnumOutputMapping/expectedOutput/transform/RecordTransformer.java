package fake.code.generated.transform;

import fake.code.generated.mappers.EditFilmResponseLevel1TypeMapper;
import fake.code.generated.mappers.FilmTypeMapper;
import fake.graphql.example.model.EditFilmResponseLevel1;
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

    public List<Film> filmRecordToGraphType(List<FilmRecord> input, String path) {
        return FilmTypeMapper.recordToGraphType(input, path, this);
    }

    public List<EditFilmResponseLevel1> editFilmResponseLevel1ToGraphType(
            List<TestFilmRecord> input, String path) {
        return EditFilmResponseLevel1TypeMapper.toGraphType(input, path, this);
    }

    public Film filmRecordToGraphType(FilmRecord input, String path) {
        return filmRecordToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }

    public EditFilmResponseLevel1 editFilmResponseLevel1ToGraphType(TestFilmRecord input,
                                                                    String path) {
        return editFilmResponseLevel1ToGraphType(List.of(input), path).stream().findFirst().orElse(null);
    }
}
