package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.FilmInput;
import fake.graphql.example.model.Rating;
import fake.graphql.example.model.RatingNoConverter;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import no.fellesstudentsystem.graphitron_newtestorder.codereferences.dummyreferences.DummyEnum;
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
                if (arguments.contains(pathHere + "rating1")) {
                    mapperFilmJavaRecord.setEnum1(itFilmInput.getRating1() == null ? null : Map.of(Rating.G, DummyEnum.G, Rating.PG, DummyEnum.PG, Rating.R, DummyEnum.R).getOrDefault(itFilmInput.getRating1(), null));
                }

                if (arguments.contains(pathHere + "rating2")) {
                    mapperFilmJavaRecord.setEnum2(itFilmInput.getRating2() == null ? null : Map.of(RatingNoConverter.G, "G", RatingNoConverter.PG, "PG", RatingNoConverter.R, "R").getOrDefault(itFilmInput.getRating2(), null));
                }

                if (arguments.contains(pathHere + "rating1List")) {
                    mapperFilmJavaRecord.setEnum1(itFilmInput.getRating1List() == null ? null : itFilmInput.getRating1List().stream().map(itRating -> Map.of(Rating.G, DummyEnum.G, Rating.PG, DummyEnum.PG, Rating.R, DummyEnum.R).getOrDefault(itRating, null)).collect(Collectors.toList()));
                }

                if (arguments.contains(pathHere + "rating2List")) {
                    mapperFilmJavaRecord.setEnum2(itFilmInput.getRating2List() == null ? null : itFilmInput.getRating2List().stream().map(itRatingNoConverter -> Map.of(RatingNoConverter.G, "G", RatingNoConverter.PG, "PG", RatingNoConverter.R, "R").getOrDefault(itRatingNoConverter, null)).collect(Collectors.toList()));
                }

                mapperFilmJavaRecordList.add(mapperFilmJavaRecord);
            }
        }

        return mapperFilmJavaRecordList;
    }
}
