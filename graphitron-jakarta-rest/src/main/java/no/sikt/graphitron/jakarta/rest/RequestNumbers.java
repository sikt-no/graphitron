package no.sikt.graphitron.jakarta.rest;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * The size bound on the JSON numbers a request's {@code variables} and {@code extensions} carry,
 * checked at the HTTP boundary before the engine sees them.
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
 * <p>What this bounds is the JSON number, and nothing else. A number written as text, a string
 * variable such as {@code "1e999999999"} or a long run of digits, passes, as does every literal in
 * the GraphQL document, which this class never reads: whether text is a number is the scalar's and
 * the column's to say, not the transport's. Those reach their sinks unbounded, so each sink bounds
 * what it parses. The generated code does: a column-bound {@code ID} is coerced through
 * {@code ColumnValues.fromWire}, and a node id, a cursor and an entity key through the other
 * {@code ColumnValues} entry points, all of which refuse an over-large value before anything parses
 * it. A lenient arbitrary-precision scalar (one that reads a {@code String} or a float literal as a
 * {@code BigDecimal} or {@code BigInteger}) and consumer code reading one are the consumer's to bound.
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
