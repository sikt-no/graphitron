package no.sikt.graphitron.example.service.records;

import no.sikt.graphitron.example.generated.jooq.tables.records.FilmActorRecord;

import java.util.List;

public class FilmActorInsertWrapper {
    private List<FilmActorRecord> filmActors;

    public FilmActorInsertWrapper() {
    }

    public List<FilmActorRecord> getFilmActors() {
        return filmActors;
    }

    public void setFilmActors(List<FilmActorRecord> filmActors) {
        this.filmActors = filmActors;
    }
}
