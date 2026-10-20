package no.sikt.graphitron.lsp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No test in this module bounds a wait with a numeric literal. A timed {@code get}, {@code await}
 * or {@code join} whose bound contains one, or a {@code System.nanoTime()} /
 * {@code System.currentTimeMillis()} deadline built from one, is a guess about how fast a loaded
 * runner schedules this JVM; a positive wait goes through {@link HangGuard} instead.
 *
 * <p>A genuine negative wait, one asserting that something does not happen within a window, names
 * that window as a constant whose javadoc says what it measures. The scan matches literals only,
 * so a named bound passes. Comments, string literals and text blocks are blanked before matching,
 * which is also what keeps the inline sources below from tripping the scan of this file.
 */
class FixedWaitGuardTest {

    /** A floor on scanned files: comfortably below the true count, high enough to catch a walk that reached nothing. */
    private static final int MIN_SCANNED_FILES = 50;

    private static final Pattern WAIT_CALL = Pattern.compile("\\.(get|await|join)\\(");
    private static final Pattern TIME_UNIT = Pattern.compile(
        "\\b(?:TimeUnit\\.)?(?:NANOSECONDS|MICROSECONDS|MILLISECONDS|SECONDS|MINUTES|HOURS|DAYS)\\b");
    private static final Pattern CLOCK_DEADLINE = Pattern.compile(
        "System\\.(?:nanoTime|currentTimeMillis)\\(\\)\\s*\\+([^;]*);");
    private static final Pattern NUMERIC_LITERAL = Pattern.compile("(?<![\\w.])\\d[\\d_]*(?:\\.\\d+)?[lLdDfF]?\\b");

    @Test
    void noTestWaitIsBoundedByALiteral() throws IOException {
        Path root = StoreClientBoundaryTest.moduleRoot().resolve("src/test/java");
        List<String> findings = new ArrayList<>();
        int scanned = 0;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                for (String finding : scan(Files.readString(path))) {
                    findings.add(root.relativize(path) + ":" + finding);
                }
            }
        }

        assertThat(scanned)
            .as("the walk under %s reached too few files to be scanning the module at all", root)
            .isGreaterThanOrEqualTo(MIN_SCANNED_FILES);
        assertThat(findings)
            .as("a positive wait goes through HangGuard; a negative wait names its window as a "
                + "constant saying what it measures. Offending sites:\n" + String.join("\n", findings))
            .isEmpty();
    }

    @Test
    void literalBoundIsFlagged() {
        assertThat(scan("""
            class A {
                void f() throws Exception {
                    future.get(5, TimeUnit.SECONDS);
                }
            }
            """)).containsExactly("3: future.get(5, TimeUnit.SECONDS)");
    }

    @Test
    void namedBoundPasses() {
        assertThat(scan("""
            class A {
                /** How long the server is given to stay quiet before the case calls it silent. */
                static final long QUIET_WINDOW_MILLIS = 200;
                void f() throws Exception {
                    assertThat(latch.await(QUIET_WINDOW_MILLIS, TimeUnit.MILLISECONDS)).isFalse();
                    drain.join(HangGuard.BOUND);
                }
            }
            """)).isEmpty();
    }

    @Test
    void literalJoinAndClockDeadlineAreFlagged() {
        assertThat(scan("""
            class A {
                void f() throws Exception {
                    drain.join(TimeUnit.SECONDS.toMillis(5));
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                }
            }
            """)).hasSize(2);
    }

    @Test
    void untimedCallsCommentsAndStringsPass() {
        assertThat(scan("""
            class A {
                void f() throws Exception {
                    var first = record.get(0, String.class);
                    var item = list.get(1);
                    thread.join();
                    var joined = String.join("5", parts);
                    // future.get(5, TimeUnit.SECONDS);
                    long start = System.nanoTime();
                }
            }
            """)).isEmpty();
    }

    /** Each fixed-literal wait in {@code source}, as {@code "<line>: <call>"}. */
    static List<String> scan(String source) {
        String code = blankCommentsAndLiterals(source);
        List<String> findings = new ArrayList<>();
        Matcher call = WAIT_CALL.matcher(code);
        while (call.find()) {
            int close = matchingParen(code, call.end() - 1);
            if (close < 0) continue;
            String args = code.substring(call.end(), close);
            boolean timed = TIME_UNIT.matcher(args).find()
                || (call.group(1).equals("join") && !args.isBlank());
            if (timed && NUMERIC_LITERAL.matcher(TIME_UNIT.matcher(args).replaceAll("")).find()) {
                findings.add(lineOf(code, call.start()) + ": " + receiver(code, call.start())
                    + code.substring(call.start(), close + 1));
            }
        }
        Matcher deadline = CLOCK_DEADLINE.matcher(code);
        while (deadline.find()) {
            if (NUMERIC_LITERAL.matcher(deadline.group(1)).find()) {
                findings.add(lineOf(code, deadline.start()) + ": " + deadline.group().strip());
            }
        }
        return findings;
    }

    /**
     * {@code source} with every comment, string, text block and character literal replaced by
     * spaces, newlines kept so offsets still map to lines.
     */
    private static String blankCommentsAndLiterals(String source) {
        var out = new StringBuilder(source);
        int i = 0;
        int n = source.length();
        while (i < n) {
            int end;
            if (source.startsWith("//", i)) {
                end = source.indexOf('\n', i);
                end = end < 0 ? n : end;
            } else if (source.startsWith("/*", i)) {
                end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
            } else if (source.startsWith("\"\"\"", i)) {
                end = closing(source, i + 3, "\"\"\"");
            } else if (source.charAt(i) == '"' || source.charAt(i) == '\'') {
                end = closing(source, i + 1, String.valueOf(source.charAt(i)));
            } else {
                i++;
                continue;
            }
            for (int j = i; j < end; j++) {
                if (out.charAt(j) != '\n') out.setCharAt(j, ' ');
            }
            i = end;
        }
        return out.toString();
    }

    /** The offset just past the first unescaped {@code delimiter} at or after {@code from}. */
    private static int closing(String source, int from, String delimiter) {
        for (int i = from; i < source.length(); i++) {
            if (source.charAt(i) == '\\') {
                i++;
            } else if (source.startsWith(delimiter, i)) {
                return i + delimiter.length();
            }
        }
        return source.length();
    }

    private static int matchingParen(String code, int open) {
        int depth = 0;
        for (int i = open; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '(') depth++;
            else if (c == ')' && --depth == 0) return i;
        }
        return -1;
    }

    /** The identifier the call is made on, for a readable finding. */
    private static String receiver(String code, int dot) {
        int start = dot;
        while (start > 0 && Character.isJavaIdentifierPart(code.charAt(start - 1))) start--;
        return code.substring(start, dot);
    }

    private static int lineOf(String code, int offset) {
        int line = 1;
        for (int i = 0; i < offset; i++) {
            if (code.charAt(i) == '\n') line++;
        }
        return line;
    }
}
