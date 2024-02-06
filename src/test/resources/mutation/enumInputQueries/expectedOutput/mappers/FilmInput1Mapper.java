package fake.code.generated.mappers;

import fake.graphql.example.model.FilmInput1;
import fake.graphql.example.model.RatingNoConverter;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;
import org.jooq.DSLContext;

public class FilmInput1Mapper {
    public static List<FilmRecord> toJOOQRecord(List<FilmInput1> filmInput1, String path,
                                            Set<String> arguments, DSLContext ctx) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var filmInput1RecordList = new ArrayList<FilmRecord>();

        if (filmInput1 != null) {
            for (var itFilmInput1 : filmInput1) {
                if (itFilmInput1 == null) continue;
                var filmInput1Record = new FilmRecord();
                filmInput1Record.attach(ctx.configuration());
                if (arguments.contains(pathHere + "rating")) {
                    filmInput1Record.setRating(itFilmInput1.getRating() == null ? null : Map.of(RatingNoConverter.G, "G", RatingNoConverter.PG, "PG", RatingNoConverter.R, "R").getOrDefault(itFilmInput1.getRating(), null));
                }
                if (arguments.contains(pathHere + "id")) {
                    filmInput1Record.setId(itFilmInput1.getId());
                }
                filmInput1RecordList.add(filmInput1Record);
            }
        }

        return filmInput1RecordList;
    }
}
