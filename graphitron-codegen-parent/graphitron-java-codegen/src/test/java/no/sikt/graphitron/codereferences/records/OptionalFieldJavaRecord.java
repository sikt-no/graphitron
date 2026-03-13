package no.sikt.graphitron.codereferences.records;

import java.util.Optional;

public class OptionalFieldJavaRecord {
    private String id;
    private Optional<String> name;
    private Optional<Integer> rentalDuration;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public Optional<String> getName() { return name; }
    public void setName(Optional<String> name) { this.name = name; }

    public Optional<Integer> getRentalDuration() { return rentalDuration; }
    public void setRentalDuration(Optional<Integer> rentalDuration) { this.rentalDuration = rentalDuration; }
}