package no.sikt.graphitron.emit.stub;

/**
 * A class an input object is made into by its setters: one member a primitive, one a Java enum, one
 * with a value of its own that a field the client did not send leaves alone.
 */
public final class Bean {

    private int count;
    private Mood mood;
    private String note = "untouched";

    public Bean() {}

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    public Mood getMood() {
        return mood;
    }

    public void setMood(Mood mood) {
        this.mood = mood;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }
}
