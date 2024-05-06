package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditFilmResponseLevel1;
import fake.graphql.example.model.EditFilmResponseLevel2;
import fake.graphql.example.model.Rating;
import fake.graphql.example.model.RatingNoConverter;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.fellesstudentsystem.graphitron.enums.RatingTest;
import no.fellesstudentsystem.graphitron.records.TestFilmRecord;

public class EditFilmResponseLevel1TypeMapper {
    public static List<EditFilmResponseLevel1> toGraphType(List<TestFilmRecord> testFilmRecord,
                                                           String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var select = transform.getSelect();
        var editFilmResponseLevel1List = new ArrayList<EditFilmResponseLevel1>();

        if (testFilmRecord != null) {
            for (var itTestFilmRecord : testFilmRecord) {
                if (itTestFilmRecord == null) continue;
                var editFilmResponseLevel1 = new EditFilmResponseLevel1();
                var record = itTestFilmRecord.getRecord();
                if (record != null && select.contains(pathHere + "film")) {
                    editFilmResponseLevel1.setFilm(transform.filmRecordToGraphType(record, pathHere + "film"));
                }

                if (select.contains(pathHere + "rating1")) {
                    editFilmResponseLevel1.setRating1(itTestFilmRecord.getRatingNoConverter() == null ? null : Map.of(RatingNoConverter.G, "G", RatingNoConverter.PG, "PG", RatingNoConverter.R, "R").getOrDefault(itTestFilmRecord.getRatingNoConverter(), null));
                }

                if (select.contains(pathHere + "rating2")) {
                    editFilmResponseLevel1.setRating2(itTestFilmRecord.getRatingWithConverter() == null ? null : Map.of(Rating.G, RatingTest.G, Rating.PG, RatingTest.PG, Rating.R, RatingTest.R).getOrDefault(itTestFilmRecord.getRatingWithConverter(), null));
                }

                if (select.contains(pathHere + "level2")) {
                    var level2 = new EditFilmResponseLevel2();
                    if (select.contains(pathHere + "level2/rating1")) {
                        level2.setRating1(itTestFilmRecord.getRatingNoConverter() == null ? null : Map.of(RatingNoConverter.G, "G", RatingNoConverter.PG, "PG", RatingNoConverter.R, "R").getOrDefault(itTestFilmRecord.getRatingNoConverter(), null));
                    }

                    if (select.contains(pathHere + "level2/rating2")) {
                        level2.setRating2(itTestFilmRecord.getRatingWithConverter() == null ? null : Map.of(Rating.G, RatingTest.G, Rating.PG, RatingTest.PG, Rating.R, RatingTest.R).getOrDefault(itTestFilmRecord.getRatingWithConverter(), null));
                    }

                    if (select.contains(pathHere + "level2/film")) {
                        level2.setFilm(transform.filmRecordToGraphType(record, pathHere + "level2/film"));
                    }

                    editFilmResponseLevel1.setLevel2(level2);
                }

                editFilmResponseLevel1List.add(editFilmResponseLevel1);
            }
        }

        return editFilmResponseLevel1List;
    }
}
