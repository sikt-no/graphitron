package no.sikt.graphitron.rewrite.test.conditions;

/**
 * A record bean a {@code @condition} parameter takes for the {@code FilmTitleInput} input object, so
 * the generated conditions class builds it from the delivered map; see {@link BeanConditionFixtures}.
 * The same class is a {@code @service} parameter on the same parent, so the fetchers class and the
 * conditions class each host their own builder for it.
 */
public record FilmTitleFilter(String title) {}
