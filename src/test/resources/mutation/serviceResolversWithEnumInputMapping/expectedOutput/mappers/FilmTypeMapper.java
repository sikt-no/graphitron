package fake.code.generated.mappers;

import fake.code.generated.transform.RecordTransformer;
import fake.graphql.example.model.Film;
import java.lang.String;
import java.util.ArrayList;
import java.util.List;
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
                if (select.contains(pathHere + "id")) {
                    film.setId(itFilmRecord.getId());
                }

                filmList.add(film);
            }
        }

        return filmList;
    }
}
