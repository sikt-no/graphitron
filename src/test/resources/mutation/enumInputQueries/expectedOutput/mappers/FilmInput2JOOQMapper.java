package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.FilmInput2;
import fake.graphql.example.model.Rating;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.fellesstudentsystem.graphitron.enums.RatingTest;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;

public class FilmInput2JOOQMapper {
    public static List<FilmRecord> toJOOQRecord(List<FilmInput2> filmInput2, String path,
                                            RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var filmRecordList = new ArrayList<FilmRecord>();

        if (filmInput2 != null) {
            for (var itFilmInput2 : filmInput2) {
                if (itFilmInput2 == null) continue;
                var filmRecord = new FilmRecord();
                filmRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "rating")) {
                    filmRecord.setRating(itFilmInput2.getRating() == null ? null : Map.of(Rating.G, RatingTest.G, Rating.PG, RatingTest.PG, Rating.R, RatingTest.R).getOrDefault(itFilmInput2.getRating(), null));
                }
                if (arguments.contains(pathHere + "id")) {
                    filmRecord.setId(itFilmInput2.getId());
                }
                filmRecordList.add(filmRecord);
            }
        }

        return filmRecordList;
    }
}
