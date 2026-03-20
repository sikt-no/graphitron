package no.sikt.graphitron.example.service.records;

import no.sikt.graphitron.example.generated.jooq.tables.records.FilmRecord;

import java.util.List;

public class FilmInsertWrapper {
    private List<FilmRecord> films;

    public FilmInsertWrapper() {
    }

    public List<FilmRecord> getFilms() {
        return films;
    }

    public void setFilms(List<FilmRecord> films) {
        this.films = films;
    }
}