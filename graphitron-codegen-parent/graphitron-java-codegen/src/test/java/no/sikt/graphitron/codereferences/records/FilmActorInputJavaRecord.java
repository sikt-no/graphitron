package no.sikt.graphitron.codereferences.records;

import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.FilmActorRecord;
import no.sikt.graphitron.jooq.generated.testdata.public_.tables.records.RentalRecord;

public class FilmActorInputJavaRecord {
    private FilmActorRecord filmActor;
    private RentalRecord rental;

    public RentalRecord getRental() {
        return rental;
    }

    public void setRental(RentalRecord rental) {
        this.rental = rental;
    }

    public FilmActorRecord getFilmActor() {
        return filmActor;
    }

    public void setFilmActor(FilmActorRecord filmActor) {
        this.filmActor = filmActor;
    }
}
