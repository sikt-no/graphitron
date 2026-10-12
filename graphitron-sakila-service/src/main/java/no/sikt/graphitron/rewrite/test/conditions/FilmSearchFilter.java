package no.sikt.graphitron.rewrite.test.conditions;

/**
 * A setter bean with a nested bean member, mirroring {@code FilmSearchInput}, which a
 * {@code @condition} parameter takes through a dotted {@code argMapping}; see
 * {@link BeanConditionFixtures}.
 */
public class FilmSearchFilter {
    private String title;
    private FilmLengthRange length;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public FilmLengthRange getLength() { return length; }
    public void setLength(FilmLengthRange length) { this.length = length; }
}
