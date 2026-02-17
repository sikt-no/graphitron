package no.sikt.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.FilmRecord;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.LanguageRecord;

public class FilmJavaRecord {
    FilmRecord film;

    public FilmRecord getFilm() {
        return film;
    }
    public void setFilm(FilmRecord film) {
        this.film = film;
    }
}
