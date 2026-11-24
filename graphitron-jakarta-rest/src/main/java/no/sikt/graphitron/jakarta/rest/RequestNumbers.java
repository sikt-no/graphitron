package no.sikt.graphitron.jakarta.rest;

import graphql.org.antlr.v4.runtime.BaseErrorListener;
import graphql.org.antlr.v4.runtime.CharStreams;
import graphql.org.antlr.v4.runtime.RecognitionException;
import graphql.org.antlr.v4.runtime.Recognizer;
import graphql.org.antlr.v4.runtime.Token;
import graphql.parser.antlr.GraphqlLexer;

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
 * <p>A number in the GraphQL document is bounded the same way, by {@link #documentRefusal}. The
 * engine's parser builds an arbitrary-precision number for every integer and float token, whatever
 * type the token is headed for and before any scalar reads it, at a cost that grows with the square
 * of the token's length, and its own limits count a token as one whatever its length. Nothing but
 * the transport stands in front of that.
 *
 * <p>The tokens are found with the parser's own lexer, not with a scanner written here. Where a
 * string or a comment ends is the lexer's to say, and a second reading of that grammar that differs
 * from the first in one case leaves a number the check passed over and the parser then builds: a
 * block string closed by the quotes of a {@code \"""} that nothing closes later is such a case.
 *
 * <p>That lexer spends some two hundred times as long on a character of a numeric token as on any
 * other character of a document, so the document's numeric tokens are bounded together as well:
 * {@link #MAX_LITERAL_CHARACTERS} characters between them. A document that is mostly digits
 * otherwise costs every parse of it seconds with no single token out of bounds, and would cost
 * this check the same. For the same reason the lexer never meets a long run of digits here: see
 * {@link #withLongRunsCut(String)}.
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

    /**
     * The most characters a document's integer and float tokens may be written with between them,
     * signs, points and exponents included. Ten thousand is a thousand ten-digit numbers in one
     * document, and holds what the lexer spends on a document's numbers to about what it spends on
     * the rest of the largest document the parser accepts.
     */
    static final int MAX_LITERAL_CHARACTERS = 10_000;

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
     * Why a GraphQL {@code document} is refused for the numbers written in it, or {@code null} when
     * there is no reason. It is refused when an integer or float token has more than
     * {@link #MAX_DIGITS} digits written out in full, by the rule {@link #withinBounds(Object)}
     * applies to a JSON number, or has more than that many digits in a row in any part of it; and
     * when its integer and float tokens are written with more than {@link #MAX_LITERAL_CHARACTERS}
     * characters between them.
     *
     * <p>A token is what the engine's lexer says is one, so digits in a string, a block string, a
     * comment or a name are not a number here. The cost is linear in the document: one pass over
     * its characters, then one pass of the lexer that ends at the first token past a bound and
     * never meets a run of digits longer than the first pass left. It runs before anything parses
     * the document.
     *
     * <p>Lexing ends without a refusal at the first character the lexer cannot place. Every parse
     * of such a document fails at that same character, before any token is built into a number,
     * so nothing reads what follows it.
     *
     * @param document the request's {@code query}, or {@code null}
     * @return the message for the request error, or {@code null}
     */
    static String documentRefusal(String document) {
        if (document == null) {
            return null;
        }
        GraphqlLexer lexer = new GraphqlLexer(CharStreams.fromString(withLongRunsCut(document)));
        lexer.removeErrorListeners();
        lexer.addErrorListener(STOP_AT_FIRST_ERROR);
        int written = 0;
        try {
            for (Token token = lexer.nextToken(); token.getType() != Token.EOF; token = lexer.nextToken()) {
                if (token.getType() != GraphqlLexer.IntValue && token.getType() != GraphqlLexer.FloatValue) {
                    continue;
                }
                String literal = token.getText();
                written += literal.length();
                if (written > MAX_LITERAL_CHARACTERS) {
                    return "The numbers in the GraphQL document are written with more than "
                        + MAX_LITERAL_CHARACTERS + " characters in all.";
                }
                if (!literalWithinBounds(literal)) {
                    return "A number in the GraphQL document has more than " + MAX_DIGITS
                        + " digits written out in full.";
                }
            }
        } catch (Unlexable stop) {
            // The document does not parse; see the method comment.
        }
        return null;
    }

    /**
     * {@code document} with every run of more than {@code MAX_DIGITS + 1} digits cut to its first
     * {@code MAX_DIGITS + 1}, and the document itself where it has no such run, which is nearly
     * always. Only the check reads the cut text; the engine is handed the document.
     *
     * <p>The cut changes no token's kind and moves no token's edge relative to its neighbours: no
     * rule of the grammar tells a thousand and one digits in a row from more, in a number, a name,
     * a string or a comment. A numeric token that held such a run still holds one past
     * {@link #MAX_DIGITS} and is refused for it, and every other token reads as it did. What the
     * cut buys is that the lexer, slow on the digits of a number, is never handed a long one.
     */
    private static String withLongRunsCut(String document) {
        int length = document.length();
        StringBuilder cut = null;
        int copied = 0;
        int run = 0;
        for (int at = 0; at <= length; at++) {
            if (at < length && isDigit(document.charAt(at))) {
                run++;
                continue;
            }
            if (run > MAX_DIGITS + 1) {
                if (cut == null) {
                    cut = new StringBuilder(length);
                }
                cut.append(document, copied, at - run + MAX_DIGITS + 1);
                copied = at;
            }
            run = 0;
        }
        return cut == null ? document : cut.append(document, copied, length).toString();
    }

    /**
     * Whether a numeric token is within the bound: no part of it has more than {@link #MAX_DIGITS}
     * digits in a row, which also keeps building the number below cheap, and its plain decimal form
     * has no more than that many digits. An exponent {@link BigDecimal} cannot hold is out of bounds.
     */
    private static boolean literalWithinBounds(String literal) {
        int run = 0;
        for (int at = 0; at < literal.length(); at++) {
            run = isDigit(literal.charAt(at)) ? run + 1 : 0;
            if (run > MAX_DIGITS) {
                return false;
            }
        }
        try {
            return withinBounds(new BigDecimal(literal));
        } catch (NumberFormatException exponentOutOfRange) {
            return false;
        }
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /** Thrown by {@link #STOP_AT_FIRST_ERROR} to leave the lexer; carries nothing, so it is shared. */
    private static final class Unlexable extends RuntimeException {
        private static final long serialVersionUID = 1L;
        static final Unlexable INSTANCE = new Unlexable();

        private Unlexable() {
            super(null, null, false, false);
        }
    }

    /**
     * Ends lexing at the first error, as the parser's own listener does. Without it the lexer
     * recovers one character at a time, raising an exception for each.
     */
    private static final BaseErrorListener STOP_AT_FIRST_ERROR = new BaseErrorListener() {
        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
                                int charPositionInLine, String message, RecognitionException cause) {
            throw Unlexable.INSTANCE;
        }
    };

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
