package no.sikt.graphitron.jakarta.rest;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * The size bound on the numbers a request carries, the JSON numbers of its {@code variables} and
 * {@code extensions} and the integer and float tokens of its document, checked at the HTTP boundary
 * before the engine sees them.
 *
 * <p>The JSON binder decodes every number in those untyped maps as an arbitrary-precision value,
 * and a few bytes of exponent notation stand for a number whose plain decimal form has billions of
 * digits. Scalar coercion, the engine's built-ins and any custom scalar alike, and consumer code
 * reading an argument may expand such a value in full, at a cost that grows with the exponent rather
 * than with the request. Refusing a number whose plain decimal form is longer than
 * {@link #MAX_DIGITS} keeps every expansion of a decoded JSON number small, and the check itself
 * costs time in proportion to the request's size: each value is visited once, iteratively, so a
 * deeply nested request cannot exhaust the stack either.
 *
 * <p>Only {@link BigDecimal} and {@link BigInteger} are unbounded; every other {@link Number} the
 * JDK has is fixed-width and passes unchecked.
 *
 * <p>A number in the GraphQL document is bounded the same way, by {@link #literalsWithinBounds}.
 * The engine's parser builds an arbitrary-precision number for every integer and float token,
 * whatever type the token is headed for and before any scalar reads it, so nothing but the transport
 * stands in front of that. The document is read as characters, once: the digits of each numeric
 * token are counted, and no number is built.
 *
 * <p>What this bounds is the number, as JSON and as the document's grammar spell one, and nothing
 * else. A number written as text, a string variable or a string literal such as
 * {@code "1e999999999"} or a long run of digits, passes: whether text is a number is the scalar's and
 * the column's to say, not the transport's. Text reaches its sink unbounded, so each sink bounds what
 * it parses. The generated code does: a column-bound {@code ID} is coerced through
 * {@code ColumnValues.fromWire}, a node id, a cursor and an entity key through the other
 * {@code ColumnValues} entry points, and a scalar declared with {@code @scalarType} is registered
 * with its coercing behind the generated schema's {@code BoundedCoercing}, each of which refuses an
 * over-large value before anything parses it. Consumer code that reads text as a number by other
 * means is the consumer's to bound.
 */
final class RequestNumbers {

    /**
     * The most digits a number's plain decimal form may have: the integer digits of
     * {@code 1e308} and the fraction digits of {@code 4.9e-324}, the extremes of a {@code double},
     * both fit with room to spare.
     */
    static final int MAX_DIGITS = 1000;

    /**
     * A bit length past which a value certainly has more than {@link #MAX_DIGITS} digits (a digit
     * carries less than four bits), so the exact digit count is never computed on a large value.
     */
    private static final int MAX_BITS = 4 * (MAX_DIGITS + 1);

    private RequestNumbers() {}

    /**
     * Whether every number in {@code root}, at any depth of maps and lists, is within
     * {@link #MAX_DIGITS}.
     *
     * @param root a decoded JSON value: a map, a list, a scalar, or {@code null}
     * @return {@code false} as soon as one number is out of bounds
     */
    static boolean withinBounds(Object root) {
        Deque<Object> pending = new ArrayDeque<>();
        if (root != null) {
            pending.push(root);
        }
        while (!pending.isEmpty()) {
            Object value = pending.pop();
            if (value instanceof Map<?, ?> map) {
                for (Object member : map.values()) {
                    if (member != null) {
                        pending.push(member);
                    }
                }
            } else if (value instanceof Iterable<?> elements) {
                for (Object element : elements) {
                    if (element != null) {
                        pending.push(element);
                    }
                }
            } else if (value instanceof BigDecimal decimal) {
                if (!withinBounds(decimal)) {
                    return false;
                }
            } else if (value instanceof BigInteger integer) {
                if (!withinBounds(new BigDecimal(integer))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Whether every integer and float token in a GraphQL {@code document} has at most
     * {@link #MAX_DIGITS} digits written out in full, by the rule {@link #withinBounds(Object)}
     * applies to a JSON number.
     *
     * <p>A token is found as the engine's lexer finds one: strings, block strings, comments and names
     * are passed over, so digits inside them are not a number here. The scan is one pass over the
     * characters and builds nothing, so its cost is linear in the document whatever the document
     * holds, and it runs before anything parses the document.
     *
     * @param document the request's {@code query}, or {@code null}
     * @return {@code false} as soon as one numeric token is out of bounds
     */
    static boolean literalsWithinBounds(String document) {
        if (document == null) {
            return true;
        }
        int length = document.length();
        int at = 0;
        while (at < length) {
            char c = document.charAt(at);
            if (c == '#') {
                at = endOfComment(document, at);
            } else if (c == '"') {
                at = endOfString(document, at);
            } else if (isNameStart(c)) {
                at = endOfName(document, at);
            } else if (isDigit(c) || (c == '-' && at + 1 < length && isDigit(document.charAt(at + 1)))) {
                int end = endOfNumber(document, at);
                if (!tokenWithinBounds(document, at, end)) {
                    return false;
                }
                at = end;
            } else {
                at++;
            }
        }
        return true;
    }

    /** The index after a comment that starts at {@code at}: it runs to the end of its line. */
    private static int endOfComment(String document, int at) {
        int end = at;
        while (end < document.length() && document.charAt(end) != '\n' && document.charAt(end) != '\r') {
            end++;
        }
        return end;
    }

    /**
     * The index after the string that starts at {@code at}, a block string where three quotes open
     * it. A block string ends at the next three quotes not written {@code \"""}; a string ends at the
     * next quote not behind a backslash, or at the end of its line where it is never closed.
     */
    private static int endOfString(String document, int at) {
        int length = document.length();
        if (document.startsWith("\"\"\"", at)) {
            int end = at + 3;
            while (end < length) {
                if (document.startsWith("\\\"\"\"", end)) {
                    end += 4;
                } else if (document.startsWith("\"\"\"", end)) {
                    return end + 3;
                } else {
                    end++;
                }
            }
            return length;
        }
        int end = at + 1;
        while (end < length) {
            char c = document.charAt(end);
            if (c == '\\') {
                end += 2;
            } else if (c == '"') {
                return end + 1;
            } else if (c == '\n' || c == '\r') {
                return end;
            } else {
                end++;
            }
        }
        return length;
    }

    /** The index after the name that starts at {@code at}, whose later characters may be digits. */
    private static int endOfName(String document, int at) {
        int end = at + 1;
        while (end < document.length() && (isNameStart(document.charAt(end)) || isDigit(document.charAt(end)))) {
            end++;
        }
        return end;
    }

    /**
     * The index after the numeric token that starts at {@code at}: a sign, digits, a point followed
     * by digits, and an exponent with its own sign and digits, each part only where it is present.
     */
    private static int endOfNumber(String document, int at) {
        int length = document.length();
        int end = at;
        if (document.charAt(end) == '-') {
            end++;
        }
        while (end < length && isDigit(document.charAt(end))) {
            end++;
        }
        if (end + 1 < length && document.charAt(end) == '.' && isDigit(document.charAt(end + 1))) {
            end++;
            while (end < length && isDigit(document.charAt(end))) {
                end++;
            }
        }
        if (end < length && (document.charAt(end) == 'e' || document.charAt(end) == 'E')) {
            int digits = end + 1;
            if (digits < length && (document.charAt(digits) == '+' || document.charAt(digits) == '-')) {
                digits++;
            }
            if (digits < length && isDigit(document.charAt(digits))) {
                end = digits;
                while (end < length && isDigit(document.charAt(end))) {
                    end++;
                }
            }
        }
        return end;
    }

    /**
     * Whether the numeric token {@code document[from, to)} has at most {@link #MAX_DIGITS} digits
     * written out in full, counted as {@link #withinBounds(BigDecimal)} counts them: the precision is
     * the digits from the first that is not zero, and the scale is the fraction's digits less the
     * exponent.
     */
    private static boolean tokenWithinBounds(String document, int from, int to) {
        int at = from;
        if (document.charAt(at) == '-') {
            at++;
        }
        long significant = 0;
        long fraction = 0;
        boolean point = false;
        for (; at < to; at++) {
            char c = document.charAt(at);
            if (c == '.') {
                point = true;
            } else if (c == 'e' || c == 'E') {
                break;
            } else {
                if (significant > 0 || c != '0') {
                    significant++;
                }
                if (point) {
                    fraction++;
                }
            }
        }
        if (significant > MAX_DIGITS) {
            return false;
        }
        long exponent = 0;
        boolean negative = false;
        if (at < to) {
            at++;
            if (document.charAt(at) == '+' || document.charAt(at) == '-') {
                negative = document.charAt(at) == '-';
                at++;
            }
            for (; at < to; at++) {
                exponent = exponent * 10 + (document.charAt(at) - '0');
                if (exponent > Integer.MAX_VALUE) {
                    // Past any scale a fraction this document can hold could bring back within bounds.
                    return false;
                }
            }
        }
        long scale = fraction - (negative ? -exponent : exponent);
        if (scale < -MAX_DIGITS || scale > MAX_DIGITS) {
            return false;
        }
        long precision = Math.max(1, significant);
        long digits = scale <= 0 ? precision - scale : Math.max(precision, scale);
        return digits <= MAX_DIGITS;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean isNameStart(char c) {
        return c == '_' || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
    }

    /**
     * Whether {@code value}'s plain decimal form has at most {@link #MAX_DIGITS} digits. That form
     * has {@code precision - scale} digits when the scale is not positive (the unscaled digits and
     * {@code -scale} trailing zeros), and {@code max(precision, scale)} digits otherwise (a fraction
     * of {@code scale} digits, plus any integer digits). Read from the scale and the unscaled
     * value's bit length first, so nothing proportional to the exponent is ever computed.
     */
    private static boolean withinBounds(BigDecimal value) {
        long scale = value.scale();
        if (scale < -MAX_DIGITS || scale > MAX_DIGITS) {
            return false;
        }
        if (value.unscaledValue().bitLength() > MAX_BITS) {
            return false;
        }
        long precision = value.precision();
        long digits = scale <= 0 ? precision - scale : Math.max(precision, scale);
        return digits <= MAX_DIGITS;
    }
}
