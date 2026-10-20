package no.sikt.graphitron.rewrite;

import org.jooq.Condition;
import org.jooq.Table;

import java.util.List;
import java.util.Map;

/**
 * Fixtures for which filter slots a {@code @condition(override: true)} reads: beans bound by the
 * input's GraphQL field names whatever {@code @field(name:)} says, and methods that read some, all
 * or none of what the override suppresses. Every method is a binding-shape fixture only; none is
 * called.
 */
public final class TestConditionReaderStub {

    private TestConditionReaderStub() {}

    public static Condition headingSetter(Table<?> table, HeadingBean input) {
        throw new UnsupportedOperationException();
    }

    public static Condition headingRecord(Table<?> table, HeadingRecord input) {
        throw new UnsupportedOperationException();
    }

    public static Condition languageNameSetter(Table<?> table, LanguageNameBean input) {
        throw new UnsupportedOperationException();
    }

    public static Condition titleOnly(Table<?> table, TitleOnlyBean input) {
        throw new UnsupportedOperationException();
    }

    public static Condition wholeMap(Table<?> table, Map<String, Object> input) {
        throw new UnsupportedOperationException();
    }

    public static Condition oneString(Table<?> table, String value) {
        throw new UnsupportedOperationException();
    }

    public static Condition nothing(Table<?> table) {
        throw new UnsupportedOperationException();
    }

    /** An input-field condition that takes its own field's value and ignores it. */
    public static Condition ignoresTitle(Table<?> table, String title) {
        throw new UnsupportedOperationException();
    }

    public static Condition nestedSetter(Table<?> table, NestedBean input) {
        throw new UnsupportedOperationException();
    }

    public static Condition groupedDescription(Table<?> table, DescriptionBean input) {
        throw new UnsupportedOperationException();
    }

    public static Condition groupedBoth(Table<?> table, DescriptionLengthBean input) {
        throw new UnsupportedOperationException();
    }

    public static Condition listedSetter(Table<?> table, ListedBean input) {
        throw new UnsupportedOperationException();
    }

    /** A setter bean for {@code heading: String @field(name: "title")}, named after the field. */
    public static class HeadingBean {
        private String heading;

        public void setHeading(String heading) { this.heading = heading; }
        public String getHeading() { return heading; }
    }

    /** The record twin of {@link HeadingBean}. */
    public record HeadingRecord(String heading) {}

    /** A setter bean for a {@code @field} + {@code @reference} field, named after the field. */
    public static class LanguageNameBean {
        private String languageName;

        public void setLanguageName(String languageName) { this.languageName = languageName; }
        public String getLanguageName() { return languageName; }
    }

    /** A setter bean with a member for {@code title} and nothing else. */
    public static class TitleOnlyBean {
        private String title;

        public void setTitle(String title) { this.title = title; }
        public String getTitle() { return title; }
    }

    /** A setter bean with {@code title} and a nested bean for {@code inner}. */
    public static class NestedBean {
        private String title;
        private DescriptionBean inner;

        public void setTitle(String title) { this.title = title; }
        public void setInner(DescriptionBean inner) { this.inner = inner; }
        public String getTitle() { return title; }
        public DescriptionBean getInner() { return inner; }
    }

    /** A setter bean with a member for {@code description} and nothing else. */
    public static class DescriptionBean {
        private String description;

        public void setDescription(String description) { this.description = description; }
        public String getDescription() { return description; }
    }

    /** A setter bean with members for {@code description} and {@code length}. */
    public static class DescriptionLengthBean {
        private String description;
        private Integer length;

        public void setDescription(String description) { this.description = description; }
        public void setLength(Integer length) { this.length = length; }
        public String getDescription() { return description; }
        public Integer getLength() { return length; }
    }

    /** A setter bean reading a list of input objects through a list-shaped nested binding. */
    public static class ListedBean {
        private List<TitleOnlyBean> titles;

        public void setTitles(List<TitleOnlyBean> titles) { this.titles = titles; }
        public List<TitleOnlyBean> getTitles() { return titles; }
    }
}
