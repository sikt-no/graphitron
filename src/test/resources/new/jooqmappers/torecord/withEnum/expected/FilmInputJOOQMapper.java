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
                if (arguments.contains(pathHere + "rating1")) {
                    filmRecord.setRating(itFilmInput.getRating1() == null ? null : Map.of(Rating.G, DummyEnum.G, Rating.PG, DummyEnum.PG, Rating.R, DummyEnum.R).getOrDefault(itFilmInput.getRating1(), null));
                }

                if (arguments.contains(pathHere + "rating2")) {
                    filmRecord.setRating(itFilmInput.getRating2() == null ? null : Map.of(RatingNoConverter.G, "G", RatingNoConverter.PG, "PG", RatingNoConverter.R, "R").getOrDefault(itFilmInput.getRating2(), null));
                }

                if (arguments.contains(pathHere + "rating1List")) {
                    filmRecord.setRating(itFilmInput.getRating1List() == null ? null : itFilmInput.getRating1List().stream().map(itRating -> Map.of(Rating.G, DummyEnum.G, Rating.PG, DummyEnum.PG, Rating.R, DummyEnum.R).getOrDefault(itRating, null)).collect(Collectors.toList()));
                }

                if (arguments.contains(pathHere + "rating2List")) {
                    filmRecord.setRating(itFilmInput.getRating2List() == null ? null : itFilmInput.getRating2List().stream().map(itRatingNoConverter -> Map.of(RatingNoConverter.G, "G", RatingNoConverter.PG, "PG", RatingNoConverter.R, "R").getOrDefault(itRatingNoConverter, null)).collect(Collectors.toList()));
                }

                filmRecordList.add(filmRecord);
            }
        }

        return filmRecordList;
    }
}
