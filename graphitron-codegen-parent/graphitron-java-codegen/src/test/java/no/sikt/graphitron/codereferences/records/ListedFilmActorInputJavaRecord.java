package no.sikt.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.FilmActorRecord;

import java.util.List;

public class ListedFilmActorInputJavaRecord {
    private List<FilmActorRecord> filmActor;

    public List<FilmActorRecord> getFilmActor() {
        return filmActor;
    }

    public void setFilmActor(List<FilmActorRecord> filmActor) {
        this.filmActor = filmActor;
    }
}