package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Film;
import fake.graphql.example.model.Rating;
import fake.graphql.example.model.RatingNoConverter;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyEnum;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;

public class FilmTypeMapper {
    public static List<Film> recordToGraphType(List<FilmRecord> filmRecord, String path,
                                               RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var filmList = new ArrayList<Film>();

        if (filmRecord != null) {
            for (var itFilmRecord : filmRecord) {
                if (itFilmRecord == null) continue;
                var film = new Film();
                if (select.contains(pathHere + "rating1")) {
                    film.setRating1(itFilmRecord.getRating() == null ? null : Map.of(DummyEnum.G, Rating.G, DummyEnum.PG, Rating.PG,  DummyEnum.R, Rating.R).getOrDefault(itFilmRecord.getRating(), null));
                }

                if (select.contains(pathHere + "rating2")) {
                    film.setRating2(itFilmRecord.getRating() == null ? null : Map.of("G", RatingNoConverter.G, "PG", RatingNoConverter.PG, "R", RatingNoConverter.R).getOrDefault(itFilmRecord.getRating(), null));
                }

                filmList.add(film);
            }
        }

        return filmList;
    }
}
