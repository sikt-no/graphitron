package no.sikt.graphitron.rewrite.test.internal;

import org.jooq.Converter;
import org.jooq.DataType;
import org.jooq.impl.DefaultDataType;

/**
 * A user type over a database type jOOQ has no built-in SQL type for, a Postgres range, as the
 * cursor tests need one: its database value is the range text {@code [lower,upper)}, and its
 * {@code toString()} is the record default, deliberately unlike that text, so a reading that took
 * the user value's text for the database value's fails.
 */
record Span(String lower, String upper) {

    /**
     * The column type jOOQ codegen gives an unknown database type with a forced converter: no SQL
     * data type, and {@code Object} as the converter's database class.
     */
    static final DataType<Span> TYPE = DefaultDataType.getDefaultDataType("\"pg_catalog\".\"tstzrange\"")
        .asConvertedDataType(new RangeTextConverter());

    /**
     * The same column with a converter that declares {@code String} as its database class, which
     * only a hand-built type can: codegen's is always {@code Object}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static final DataType<Span> NARROW_TYPE = ((DataType) DefaultDataType.getDefaultDataType("\"pg_catalog\".\"tstzrange\""))
        .asConvertedDataType(Converter.<String, Span>ofNullable(String.class, Span.class, Span::parse, Span::text));

    String text() {
        return "[" + lower + "," + upper + ")";
    }

    /** The span {@code text} writes; {@code IllegalArgumentException} when it is no range text. */
    static Span parse(String text) {
        int comma = text.indexOf(',');
        if (comma < 0 || !text.startsWith("[") || !text.endsWith(")")) {
            throw new IllegalArgumentException("not a range: " + text);
        }
        return new Span(text.substring(1, comma), text.substring(comma + 1, text.length() - 1));
    }

    /** Reads the database value's {@code toString()}, as jOOQ's own range converters do. */
    static final class RangeTextConverter implements Converter<Object, Span> {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        @Override public Span from(Object databaseObject) { return databaseObject == null ? null : parse(databaseObject.toString()); }
        @Override public Object to(Span userObject) { return userObject == null ? null : userObject.text(); }
        @Override public Class<Object> fromType() { return Object.class; }
        @Override public Class<Span> toType() { return Span.class; }
    }
}
