package fake.code.generated.transform;

import fake.code.generated.mappers.FilmInput1JOOQMapper;
import fake.code.generated.mappers.FilmInput2JOOQMapper;
import fake.graphql.example.model.FilmInput1;
import fake.graphql.example.model.FilmInput2;
import graphql.schema.DataFetchingEnvironment;
import java.lang.String;
import java.util.List;
import no.fellesstudentsystem.graphql.helpers.transform.AbstractTransformer;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;
import org.jooq.DSLContext;

public class RecordTransformer extends AbstractTransformer {
    public RecordTransformer(DataFetchingEnvironment env, DSLContext ctx) {
        super(env, ctx);
    }

    public List<FilmRecord> filmInput2ToJOOQRecord(List<FilmInput2> input, String path) {
        return FilmInput2JOOQMapper.toJOOQRecord(input, path, this);
    }

    public List<FilmRecord> filmInput1ToJOOQRecord(List<FilmInput1> input, String path) {
        return FilmInput1JOOQMapper.toJOOQRecord(input, path, this);
    }

    public FilmRecord filmInput2ToJOOQRecord(FilmInput2 input, String path) {
        return filmInput2ToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new FilmRecord());
    }

    public FilmRecord filmInput1ToJOOQRecord(FilmInput1 input, String path) {
        return filmInput1ToJOOQRecord(List.of(input), path).stream().findFirst().orElse(new FilmRecord());
    }
}
