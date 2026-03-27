package no.sikt.graphitron.example.service.records;

public class WrappedFieldResult {
    private String title;
    private String description;
    private Integer rentalDuration;

    public WrappedFieldResult() {
    }

    public WrappedFieldResult(String title, String description, Integer rentalDuration) {
        this.title = title;
        this.description = description;
        this.rentalDuration = rentalDuration;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public Integer getRentalDuration() {
        return rentalDuration;
    }

    public void setRentalDuration(Integer rentalDuration) {
        this.rentalDuration = rentalDuration;
    }
}
