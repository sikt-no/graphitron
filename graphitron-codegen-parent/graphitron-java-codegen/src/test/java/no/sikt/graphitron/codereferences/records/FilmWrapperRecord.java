package no.sikt.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.FilmRecord;

public class FilmWrapperRecord {
    public FilmRecord getFilm() {
        return film;
    }

    public void setFilm(FilmRecord film) {
        this.film = film;
    }

    private FilmRecord film;
}
