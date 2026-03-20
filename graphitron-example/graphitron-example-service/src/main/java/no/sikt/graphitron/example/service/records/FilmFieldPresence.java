package no.sikt.graphitron.example.service.records;

public class FilmFieldPresence {
    private String title;
    private Boolean rentalDurationPresent;

    public FilmFieldPresence() {
    }

    public FilmFieldPresence(String title, Boolean rentalDurationPresent) {
        this.title = title;
        this.rentalDurationPresent = rentalDurationPresent;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Boolean getRentalDurationPresent() {
        return rentalDurationPresent;
    }

    public void setRentalDurationPresent(Boolean rentalDurationPresent) {
        this.rentalDurationPresent = rentalDurationPresent;
    }
}