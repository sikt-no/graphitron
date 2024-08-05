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
                if (select.contains(pathHere + "enum1")) {
                    film.setEnum1(itMapperFilmJavaRecord.getEnum1() == null ? null : Map.of("A", DummyEnum.A, "B", DummyEnum.B, "C", DummyEnum.C).getOrDefault(itMapperFilmJavaRecord.getEnum1(), null));
                }

                if (select.contains(pathHere + "enum2")) {
                    film.setEnum2(itMapperFilmJavaRecord.getEnum2() == null ? null : Map.of(DummyJOOQEnum.A, DummyEnumConverted.A, DummyJOOQEnum.B, DummyEnumConverted.B, DummyJOOQEnum.C, DummyEnumConverted.C).getOrDefault(itMapperFilmJavaRecord.getEnum2(), null));
                }

                if (select.contains(pathHere + "enum1List")) {
                    film.setEnum1List(itMapperFilmJavaRecord.getEnum1List() == null ? null : itMapperFilmJavaRecord.getEnum1List().stream().map(itDummyEnum -> Map.of("A", DummyEnum.A, "B", DummyEnum.B, "C", DummyEnum.C).getOrDefault(itDummyEnum, null)).collect(Collectors.toList()));
                }

                if (select.contains(pathHere + "enum2List")) {
                    film.setEnum2List(itMapperFilmJavaRecord.getEnum2List() == null ? null : itMapperFilmJavaRecord.getEnum2List().stream().map(itDummyEnumConverted -> Map.of(DummyJOOQEnum.A, DummyEnumConverted.A, DummyJOOQEnum.B, DummyEnumConverted.B, DummyJOOQEnum.C, DummyEnumConverted.C).getOrDefault(itDummyEnumConverted, null)).collect(Collectors.toList()));
                }

                filmList.add(film);
            }
        }

        return filmList;
    }
}
