package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.EditInputLevel1;
import fake.graphql.example.model.Rating;
import fake.graphql.example.model.RatingNoConverter;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import no.fellesstudentsystem.graphitron.enums.RatingTest;
import no.fellesstudentsystem.graphitron.records.TestFilmRecord;

public class EditInputLevel1JavaMapper {
    public static List<TestFilmRecord> toJavaRecord(List<EditInputLevel1> editInputLevel1,
                                                    String path, RecordTransformer transform) {
        var pathHere = path.isEmpty() ? path : path + "/";
        var arguments = transform.getArguments();
        var testFilmRecordList = new ArrayList<TestFilmRecord>();


        if (editInputLevel1 != null) {
            for (var itEditInputLevel1 : editInputLevel1) {
                if (itEditInputLevel1 == null) continue;
                var testFilmRecord = new TestFilmRecord();
                if (arguments.contains(pathHere + "rating1")) {
                    testFilmRecord.setRatingNoConverter(itEditInputLevel1.getRating1() == null ? null : Map.of(RatingNoConverter.G, "G", RatingNoConverter.PG, "PG", RatingNoConverter.R, "R").getOrDefault(itEditInputLevel1.getRating1(), null));
                }

                if (arguments.contains(pathHere + "rating2")) {
                    testFilmRecord.setRatingWithConverter(itEditInputLevel1.getRating2() == null ? null : Map.of(Rating.G, RatingTest.G, Rating.PG, RatingTest.PG, Rating.R, RatingTest.R).getOrDefault(itEditInputLevel1.getRating2(), null));
                }

                var edit2 = itEditInputLevel1.getEdit2();
                if (edit2 != null && arguments.contains(pathHere + "edit2")) {
                    if (arguments.contains(pathHere + "edit2/rating1")) {
                        testFilmRecord.setRatingNoConverter(edit2.getRating1() == null ? null : Map.of(RatingNoConverter.G, "G", RatingNoConverter.PG, "PG", RatingNoConverter.R, "R").getOrDefault(edit2.getRating1(), null));
                    }

                    if (arguments.contains(pathHere + "edit2/rating2")) {
                        testFilmRecord.setRatingWithConverter(edit2.getRating2() == null ? null : Map.of(Rating.G, RatingTest.G, Rating.PG, RatingTest.PG, Rating.R, RatingTest.R).getOrDefault(edit2.getRating2(), null));
                    }

                }

                testFilmRecordList.add(testFilmRecord);
            }
        }

        return testFilmRecordList;
    }
}
