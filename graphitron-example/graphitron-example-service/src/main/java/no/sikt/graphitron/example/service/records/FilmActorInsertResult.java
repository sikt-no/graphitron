package no.sikt.graphitron.example.service.records;

public class FilmActorInsertResult {
    private Integer insertedCount;

    public FilmActorInsertResult() {
    }

    public FilmActorInsertResult(Integer insertedCount) {
        this.insertedCount = insertedCount;
    }

    public Integer getInsertedCount() {
        return insertedCount;
    }

    public void setInsertedCount(Integer insertedCount) {
        this.insertedCount = insertedCount;
    }
}
