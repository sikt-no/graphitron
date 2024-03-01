package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.FilmInput1;
import fake.graphql.example.model.RatingNoConverter;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;

public class FilmInput1JOOQMapper {
    public static List<FilmRecord> toJOOQRecord(List<FilmInput1> filmInput1, String path,
                                            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var filmRecordList = new ArrayList<FilmRecord>();

        if (filmInput1 != null) {
            for (var itFilmInput1 : filmInput1) {
                if (itFilmInput1 == null) continue;
                var filmRecord = new FilmRecord();
                filmRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "rating")) {
                    filmRecord.setRating(itFilmInput1.getRating() == null ? null : Map.of(RatingNoConverter.G, "G", RatingNoConverter.PG, "PG", RatingNoConverter.R, "R").getOrDefault(itFilmInput1.getRating(), null));
                }
                if (arguments.contains(pathHere + "id")) {
                    filmRecord.setId(itFilmInput1.getId());
                }
                filmRecordList.add(filmRecord);
            }
        }

        return filmRecordList;
    }
}
