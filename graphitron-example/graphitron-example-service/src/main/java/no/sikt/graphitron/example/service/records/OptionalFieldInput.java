package no.sikt.graphitron.example.service.records;

import java.util.Optional;

public class OptionalFieldInput {
    private String title;
    private Optional<Integer> rentalDuration;
    private Optional<String> description;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Optional<Integer> getRentalDuration() {
        return rentalDuration;
    }

    public void setRentalDuration(Optional<Integer> rentalDuration) {
        this.rentalDuration = rentalDuration;
    }

    public Optional<String> getDescription() {
        return description;
    }

    public void setDescription(Optional<String> description) {
        this.description = description;
    }
}