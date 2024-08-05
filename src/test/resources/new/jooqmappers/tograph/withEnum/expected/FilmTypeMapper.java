package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.DummyEnum;
import fake.graphql.example.model.DummyEnumConverted;
import fake.graphql.example.model.Film;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyJOOQEnum;
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
                if (select.contains(pathHere + "enum1")) {
                    film.setEnum1(itFilmRecord.getRating() == null ? null : Map.of("A", DummyEnum.A, "B", DummyEnum.B, "C", DummyEnum.C).getOrDefault(itFilmRecord.getRating(), null));
                }

                if (select.contains(pathHere + "enum2")) {
                    film.setEnum2(itFilmRecord.getRating() == null ? null : Map.of(DummyJOOQEnum.A, DummyEnumConverted.A, DummyJOOQEnum.B, DummyEnumConverted.B, DummyJOOQEnum.C, DummyEnumConverted.C).getOrDefault(itFilmRecord.getRating(), null));
                }

                if (select.contains(pathHere + "enum1List")) {
                    film.setEnum1List(itFilmRecord.getRating() == null ? null : itFilmRecord.getRating().stream().map(itDummyEnum -> Map.of("A", DummyEnum.A, "B", DummyEnum.B, "C", DummyEnum.C).getOrDefault(itDummyEnum, null)).collect(Collectors.toList()));
                }

                if (select.contains(pathHere + "enum2List")) {
                    film.setEnum2List(itFilmRecord.getRating() == null ? null : itFilmRecord.getRating().stream().map(itDummyEnumConverted -> Map.of(DummyJOOQEnum.A, DummyEnumConverted.A, DummyJOOQEnum.B, DummyEnumConverted.B, DummyJOOQEnum.C, DummyEnumConverted.C).getOrDefault(itDummyEnumConverted, null)).collect(Collectors.toList()));
                }

                filmList.add(film);
            }
        }

        return filmList;
    }
}
