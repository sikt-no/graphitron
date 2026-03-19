package no.sikt.graphitron.example.service.records;

public class OptionalFieldResult {
    private String title;
    private String rentalDurationStatus;
    private String descriptionStatus;

    public OptionalFieldResult() {
    }

    public OptionalFieldResult(String title, String rentalDurationStatus, String descriptionStatus) {
        this.title = title;
        this.rentalDurationStatus = rentalDurationStatus;
        this.descriptionStatus = descriptionStatus;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRentalDurationStatus() {
        return rentalDurationStatus;
    }

    public void setRentalDurationStatus(String rentalDurationStatus) {
        this.rentalDurationStatus = rentalDurationStatus;
    }

    public String getDescriptionStatus() {
        return descriptionStatus;
    }

    public void setDescriptionStatus(String descriptionStatus) {
        this.descriptionStatus = descriptionStatus;
    }
}
