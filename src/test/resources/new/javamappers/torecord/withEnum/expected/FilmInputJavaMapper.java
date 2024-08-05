package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.DummyEnum;
import fake.graphql.example.model.DummyEnumConverted;
import fake.graphql.example.model.FilmInput;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyJOOQEnum;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.records.MapperFilmJavaRecord;

public class FilmInputJavaMapper {
    public static List<MapperFilmJavaRecord> toJavaRecord(List<FilmInput> filmInput, String path,
                                                          RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var mapperFilmJavaRecordList = new ArrayList<MapperFilmJavaRecord>();

        if (filmInput != null) {
            for (var itFilmInput : filmInput) {
                if (itFilmInput == null) continue;
                var mapperFilmJavaRecord = new MapperFilmJavaRecord();
                if (arguments.contains(pathHere + "enum1")) {
                    mapperFilmJavaRecord.setEnum1(itFilmInput.getEnum1() == null ? null : Map.of(DummyEnum.A, "A", DummyEnum.B, "B", DummyEnum.C, "C").getOrDefault(itFilmInput.getEnum1(), null));
                }

                if (arguments.contains(pathHere + "enum2")) {
                    mapperFilmJavaRecord.setEnum2(itFilmInput.getEnum2() == null ? null : Map.of(DummyEnumConverted.A, DummyJOOQEnum.A, DummyEnumConverted.B, DummyJOOQEnum.B, DummyEnumConverted.C, DummyJOOQEnum.C).getOrDefault(itFilmInput.getEnum2(), null));
                }

                if (arguments.contains(pathHere + "enum1List")) {
                    mapperFilmJavaRecord.setEnum1List(itFilmInput.getEnum1List() == null ? null : itFilmInput.getEnum1List().stream().map(itDummyEnum -> Map.of(DummyEnum.A, "A", DummyEnum.B, "B", DummyEnum.C, "C").getOrDefault(itDummyEnum, null)).collect(Collectors.toList()));
                }

                if (arguments.contains(pathHere + "enum2List")) {
                    mapperFilmJavaRecord.setEnum2List(itFilmInput.getEnum2List() == null ? null : itFilmInput.getEnum2List().stream().map(itDummyEnumConverted -> Map.of(DummyEnumConverted.A, DummyJOOQEnum.A, DummyEnumConverted.B, DummyJOOQEnum.B, DummyEnumConverted.C, DummyJOOQEnum.C).getOrDefault(itDummyEnumConverted, null)).collect(Collectors.toList()));
                }

                mapperFilmJavaRecordList.add(mapperFilmJavaRecord);
            }
        }

        return mapperFilmJavaRecordList;
    }
}
