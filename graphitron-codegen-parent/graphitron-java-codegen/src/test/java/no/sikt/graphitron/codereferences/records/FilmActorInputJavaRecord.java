package no.sikt.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.FilmActorRecord;

public class FilmActorInputJavaRecord {
    private FilmActorRecord filmActor;

    public FilmActorRecord getFilmActor() {
        return filmActor;
    }

    public void setFilmActor(FilmActorRecord filmActor) {
        this.filmActor = filmActor;
    }
}
