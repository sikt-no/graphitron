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
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperFilmJavaRecord;

public class FilmTypeMapper {
    public static List<Film> toGraphType(List<MapperFilmJavaRecord> mapperFilmJavaRecord,
                                         String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var filmList = new ArrayList<Film>();

        if (mapperFilmJavaRecord != null) {
            for (var itMapperFilmJavaRecord : mapperFilmJavaRecord) {
                if (itMapperFilmJavaRecord == null) continue;
                var film = new Film();
                if (select.contains(pathHere + "rating1")) {
                    film.setRating1(itMapperFilmJavaRecord.getEnum1() == null ? null : Map.of(DummyEnum.G, Rating.G, DummyEnum.PG, Rating.PG,  DummyEnum.R, Rating.R).getOrDefault(itMapperFilmJavaRecord.getEnum1(), null));
                }

                if (select.contains(pathHere + "rating2")) {
                    film.setRating2(itMapperFilmJavaRecord.getEnum2() == null ? null : Map.of("G", RatingNoConverter.G, "PG", RatingNoConverter.PG, "R", RatingNoConverter.R).getOrDefault(itMapperFilmJavaRecord.getEnum2(), null));
                }

                filmList.add(film);
            }
        }

        return filmList;
    }
}
