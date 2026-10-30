package no.sikt.graphitron.model.jooq;

/**
 * How a generated jOOQ record names the getter it reads a column through, and the Bean Validation
 * property that getter names. The capture confirms the name on the live record class; a reader that
 * found no getter uses the same rule to say which name it looked for.
 */
public final class RecordGetters {

    private RecordGetters() {}

    /**
     * The getter name jOOQ's {@code DefaultGeneratorStrategy} writes for a column with its defaults:
     * {@code get}, then {@link org.jooq.tools.StringUtils#toCamelCase} of the SQL name with space,
     * hyphen and dot read as underscores, which capitalises each underscore-separated word and
     * lower-cases the rest of it. {@code BILDE} gives {@code getBilde}, {@code film_id} gives
     * {@code getFilmId} and {@code FilmId} gives {@code getFilmid}.
     */
    public static String getterName(String sqlName) {
        return "get" + org.jooq.tools.StringUtils.toCamelCase(
            sqlName.replace(' ', '_').replace('-', '_').replace('.', '_'));
    }

    /**
     * The Bean Validation property a getter names: the name without {@code get}, decapitalised as
     * {@code java.beans.Introspector.decapitalize} does, a name opening on two capitals keeping them.
     */
    public static String propertyName(String getterName) {
        String bare = getterName.startsWith("get") ? getterName.substring("get".length()) : getterName;
        if (bare.length() > 1 && Character.isUpperCase(bare.charAt(0)) && Character.isUpperCase(bare.charAt(1))) {
            return bare;
        }
        return bare.isEmpty() ? bare : Character.toLowerCase(bare.charAt(0)) + bare.substring(1);
    }
}
