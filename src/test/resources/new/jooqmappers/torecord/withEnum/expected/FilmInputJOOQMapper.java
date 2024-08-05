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
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;

public class FilmInputJOOQMapper {
    public static List<FilmRecord> toJOOQRecord(List<FilmInput> filmInput, String path,
                                                RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var ctx = transform.getCtx();
        var filmRecordList = new ArrayList<FilmRecord>();

        if (filmInput != null) {
            for (var itFilmInput : filmInput) {
                if (itFilmInput == null) continue;
                var filmRecord = new FilmRecord();
                filmRecord.attach(ctx.configuration());
                if (arguments.contains(pathHere + "enum1")) {
                    filmRecord.setRating(itFilmInput.getEnum1() == null ? null : Map.of(DummyEnum.A, "A", DummyEnum.B, "B", DummyEnum.C, "C").getOrDefault(itFilmInput.getEnum1(), null));
                }

                if (arguments.contains(pathHere + "enum2")) {
                    filmRecord.setRating(itFilmInput.getEnum2() == null ? null : Map.of(DummyEnumConverted.A, DummyJOOQEnum.A, DummyEnumConverted.B, DummyJOOQEnum.B, DummyEnumConverted.C, DummyJOOQEnum.C).getOrDefault(itFilmInput.getEnum2(), null));
                }

                if (arguments.contains(pathHere + "enum1List")) {
                    filmRecord.setRating(itFilmInput.getEnum1List() == null ? null : itFilmInput.getEnum1List().stream().map(itDummyEnum -> Map.of(DummyEnum.A, "A", DummyEnum.B, "B", DummyEnum.C, "C").getOrDefault(itDummyEnum, null)).collect(Collectors.toList()));
                }

                if (arguments.contains(pathHere + "enum2List")) {
                    filmRecord.setRating(itFilmInput.getEnum2List() == null ? null : itFilmInput.getEnum2List().stream().map(itDummyEnumConverted -> Map.of(DummyEnumConverted.A, DummyJOOQEnum.A, DummyEnumConverted.B, DummyJOOQEnum.B, DummyEnumConverted.C, DummyJOOQEnum.C).getOrDefault(itDummyEnumConverted, null)).collect(Collectors.toList()));
                }

                filmRecordList.add(filmRecord);
            }
        }

        return filmRecordList;
    }
}
