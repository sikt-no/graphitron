package no.fellesstudentsystem.graphitron.records;

import no.fellesstudentsystem.graphitron.enums.RatingTest;
import no.sikt.graphitron.jooq.generated.testdata.tables.records.FilmRecord;

import java.util.List;

public class TestFilmRecord {
    private String someID, ratingNoConverter;
    private RatingTest ratingWithConverter;
    private FilmRecord record;
    private List<FilmRecord> recordList;

    public String getSomeID() {
        return someID;
    }

    public void setSomeID(String someID) {
        this.someID = someID;
    }

    public String getRatingNoConverter() {
        return ratingNoConverter;
    }

    public void setRatingNoConverter(String ratingNoConverter) {
        this.ratingNoConverter = ratingNoConverter;
    }

    public RatingTest getRatingWithConverter() {
        return ratingWithConverter;
    }

    public void setRatingWithConverter(RatingTest ratingWithConverter) {
        this.ratingWithConverter = ratingWithConverter;
    }

    public FilmRecord getRecord() {
        return record;
    }

    public void setRecord(FilmRecord record) {
        this.record = record;
    }

    public List<FilmRecord> getRecordList() {
        return recordList;
    }

    public void setRecordList(List<FilmRecord> recordList) {
        this.recordList = recordList;
    }
}
