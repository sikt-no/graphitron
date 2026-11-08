package no.sikt.graphitron.rewrite.test.services;

/**
 * A class an input object is made into by its setters: one member a primitive, one a Java enum, one
 * with a value of its own that a field the client did not send leaves alone.
 */
public final class FilmSurvey {

    private int count;
    private FilmMood mood;
    private String note = "untouched";

    public FilmSurvey() {}

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public FilmMood getMood() {
        return mood;
    }

    public void setMood(FilmMood mood) {
        this.mood = mood;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
