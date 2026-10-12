package no.sikt.graphitron.rewrite.test.conditions;

/** The nested setter bean of {@link FilmSearchFilter}, mirroring {@code FilmLengthRangeInput}. */
public class FilmLengthRange {
    private Integer min;
    private Integer max;

    public Integer getMin() { return min; }
    public void setMin(Integer min) { this.min = min; }
    public Integer getMax() { return max; }
    public void setMax(Integer max) { this.max = max; }
}
