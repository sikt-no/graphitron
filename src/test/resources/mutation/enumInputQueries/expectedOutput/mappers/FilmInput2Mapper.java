package fake.code.generated.mappers;

import fake.graphql.example.model.FilmInput2;
import fake.graphql.example.model.Rating;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.fellesstudentsystem.graphitron.enums.RatingTest;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;
import org.jooq.DSLContext;

public class FilmInput2Mapper {
    public static List<FilmRecord> toJOOQRecord(List<FilmInput2> filmInput2, String path,
                                            Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var filmInput2RecordList = new ArrayList<FilmRecord>();

        if (filmInput2 != null) {
            for (var itFilmInput2 : filmInput2) {
                if (itFilmInput2 == null) continue;
                var filmInput2Record = new FilmRecord();
                filmInput2Record.attach(ctx.configuration());
                if (arguments.contains(pathHere + "rating")) {
                    filmInput2Record.setRating(itFilmInput2.getRating() == null ? null : Map.of(Rating.G, RatingTest.G, Rating.PG, RatingTest.PG, Rating.R, RatingTest.R).getOrDefault(itFilmInput2.getRating(), null));
                }
                if (arguments.contains(pathHere + "id")) {
                    filmInput2Record.setId(itFilmInput2.getId());
                }
                filmInput2RecordList.add(filmInput2Record);
            }
        }

        return filmInput2RecordList;
    }
}
