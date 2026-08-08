package no.sikt.graphitron.roadmap;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The inert monospace-span vocabulary for generated AsciiDoc: the producers that emit one,
 * the code-span segmentation the emitters share, and the recognizer that says whether
 * emitted text already is one.
 *
 * <p>A markdown inline code span is literal by definition. A single-backtick AsciiDoc span
 * is not: it applies the normal substitution group with macros included, so a quoted xref
 * becomes a live link and a quoted attribute reference resolves, or warns. Every
 * markdown-sourced span the roadmap renderers emit therefore goes out in one of two inert
 * forms, and both the forms and the recognizer that reads them back live here, so which
 * span forms count as inert cannot drift between the emitters and the checks policing them.
 */
final class InertSpans {

    private InertSpans() {}

    /**
     * Whether {@code content} fits the constrained plus-delimited passthrough
     * {@code `+content+`}. It must be non-empty, carry no {@code +} of its own, carry no
     * backtick (which cannot sit inside a single-backtick wrapper at all), and be free of
     * leading and trailing whitespace, because a constrained formatting pair requires
     * non-space-adjacent delimiters.
     */
    static boolean plusFormFits(String content) {
        return !content.isEmpty()
            && content.indexOf('+') < 0
            && content.indexOf('`') < 0
            && !Character.isWhitespace(content.charAt(0))
            && !Character.isWhitespace(content.charAt(content.length() - 1));
    }

    /**
     * The inert monospace span for {@code content}, a total function of it: the readable
     * plus-delimited form when {@link #plusFormFits} allows it, the pass macro otherwise.
     * Both forms apply the special-characters substitution and nothing else, so the content
     * renders as typed. The pass macro survives content carrying backticks because
     * Asciidoctor extracts inline passthroughs before the quotes substitution pairs the
     * wrapping backticks.
     *
     * <p>{@code ]} is escaped as {@code \]}, the only escape the macro grammar offers.
     * Content ending in a backslash is therefore not representable; {@link #scan} reports
     * such a span rather than letting it render wrong, since its recognizer applies the
     * same terminator rule Asciidoctor does.
     */
    static String monospace(String content) {
        return plusFormFits(content)
            ? "`+" + content + "+`"
            : "`pass:c[" + content.replace("]", "\\]") + "]`";
    }

    /**
     * The inert monospace span for {@code content} inside a macro attrlist, that is, an
     * {@code xref:} or {@code link:} label. A {@code ]} terminates the attrlist lexically,
     * before inline substitutions run, so the pass macro is structurally unsafe here.
     * Content the plus-delimited form cannot carry stays a bare backtick span, which
     * {@link #scan} then reports, so the author is told to rephrase the label rather than
     * shipping a substituting span silently.
     */
    static String label(String content) {
        return plusFormFits(content) && content.indexOf(']') < 0
            ? "`+" + content + "+`"
            : "`" + content + "`";
    }

    /**
     * CommonMark's code-span pad rule: content that both begins and ends with a space but
     * does not consist entirely of spaces loses one space from each end. The pad is
     * delimiter syntax, there so content may itself start or end with a backtick, and is
     * not part of the content.
     */
    static String normalize(String content) {
        return content.length() >= 2
            && content.charAt(0) == ' '
            && content.charAt(content.length() - 1) == ' '
            && !content.isBlank()
            ? content.substring(1, content.length() - 1)
            : content;
    }

    // ===== Segmentation =====

    /** A run of consecutive backticks: one code-span delimiter. */
    record Run(int start, int length) {
        int end() {
            return start + length;
        }
    }

    /** One code span opened and closed on the same line. */
    record Span(Run open, Run close) {}

    /**
     * How one line's backtick runs pair into code spans.
     *
     * @param carriedCloser the run closing a span an earlier line opened, or null when no
     *                      span was open on entry or none closes here
     * @param spans         spans opened and closed on this line, left to right
     * @param carriedOpener the run opening a span this line does not close, or null when
     *                      the span was already open on entry or none is left open
     * @param openRun       the delimiter length left open at end of line, 0 when none
     */
    record Pairing(Run carriedCloser, List<Span> spans, Run carriedOpener, int openRun) {}

    /** Backtick runs in {@code line}, left to right. */
    static List<Run> runs(String line) {
        List<Run> out = new ArrayList<>();
        int i = 0;
        while (i < line.length()) {
            if (line.charAt(i) != '`') {
                i++;
                continue;
            }
            int j = i;
            while (j < line.length() && line.charAt(j) == '`') j++;
            out.add(new Run(i, j - i));
            i = j;
        }
        return out;
    }

    /**
     * Pairs the backtick runs on {@code line} into code spans, given the delimiter length a
     * previous line left open ({@code 0} when none). A run pairs with the next run of equal
     * length; runs of other lengths in between are content, per CommonMark. A run with no
     * partner on this line opens a span the line does not close, and pairing stops there:
     * the roadmap sources are hard-wrapped, so a span crossing a line break is ordinary, and
     * deferring to the next line is also what Asciidoctor does when it pairs backticks over
     * the joined paragraph.
     *
     * <p>Carrying the open delimiter is what keeps a hard-wrapped span from being mispaired.
     * Without it a line that closes one span and opens another pairs the closer with the
     * opener and wraps the prose between them, so the state is not a refinement but the
     * condition for line-local segmentation to be correct at all.
     */
    static Pairing pair(String line, int carriedOpen) {
        List<Run> rs = runs(line);
        int i = 0;
        Run carriedCloser = null;
        if (carriedOpen > 0) {
            while (i < rs.size() && rs.get(i).length() != carriedOpen) i++;
            if (i == rs.size()) {
                // The whole line is span content; the span stays open past it.
                return new Pairing(null, List.of(), null, carriedOpen);
            }
            carriedCloser = rs.get(i++);
        }
        List<Span> spans = new ArrayList<>();
        Run carriedOpener = null;
        while (i < rs.size()) {
            Run open = rs.get(i);
            int j = i + 1;
            while (j < rs.size() && rs.get(j).length() != open.length()) j++;
            if (j == rs.size()) {
                carriedOpener = open;
                break;
            }
            spans.add(new Span(open, rs.get(j)));
            i = j + 1;
        }
        return new Pairing(carriedCloser, spans, carriedOpener,
            carriedOpener == null ? 0 : carriedOpener.length());
    }

    // ===== Recognition =====

    /**
     * The two inert forms, as Asciidoctor reads them back. The pass-macro content group
     * mirrors Asciidoctor's own terminator rule, ending at the first {@code ]} not preceded
     * by a backslash; the plus-delimited alternative is confirmed against
     * {@link #plusFormFits} by the caller, so a would-be passthrough that is really a
     * space-adjacent plus pair is not mistaken for one.
     */
    private static final Pattern INERT = Pattern.compile(
        "`\\+([^+`]+)\\+`|`pass:c?\\[(|.*?[^\\\\])\\]`");

    /** Blanks every inert span in {@code line}, preserving every other offset. */
    static String maskInert(String line) {
        StringBuilder sb = new StringBuilder(line);
        Matcher m = INERT.matcher(line);
        int from = 0;
        while (from < line.length() && m.find(from)) {
            String plus = m.group(1);
            if (plus != null && !plusFormFits(plus)) {
                from = m.start() + 1;
                continue;
            }
            for (int k = m.start(); k < m.end(); k++) sb.setCharAt(k, ' ');
            from = m.end();
        }
        return sb.toString();
    }

    /** A monospace span in generated AsciiDoc that is not in an inert form. */
    record Finding(int line, String span) {}

    /** Structural blocks whose content is inert by block context rather than by span form. */
    private static final Pattern BLOCK_DELIMITER = Pattern.compile("-{4,}|\\.{4,}|/{4,}|\\+{4,}");

    /**
     * The verbatim-block context of an AsciiDoc document, advanced one line at a time.
     *
     * <p>Listing, literal, comment and passthrough blocks hold content that is inert by block
     * context rather than by span form, so a scan looking for live markup has to skip them. A
     * block closes only on its own delimiter at the same length, which is Asciidoctor's rule
     * too. Table blocks are deliberately not tracked: the table cell is a live surface, and
     * both {@link #scan} and {@link AdocXrefAnchorCheck} exist partly to police it.
     *
     * <p>One tracker rather than one per scan, because the set of blocks that swallow markup
     * is a property of AsciiDoc and not of either caller: a second copy would be free to drift
     * from this one the same way a second list of inert span forms would drift from
     * {@link #maskInert}.
     */
    static final class BlockContext {

        private String openBlock;

        /**
         * Advances over {@code line} and answers whether it carries flowed prose, that is,
         * whether markup on it reaches the page. A delimiter line opens or closes a block and
         * is never prose itself, and neither is anything between the two.
         */
        boolean isProse(String line) {
            String trimmed = line.strip();
            if (openBlock != null) {
                if (trimmed.equals(openBlock)) openBlock = null;
                return false;
            }
            if (BLOCK_DELIMITER.matcher(trimmed).matches()) {
                openBlock = trimmed;
                return false;
            }
            return true;
        }
    }

    /**
     * Scans one generated AsciiDoc document for monospace spans that are not in an inert
     * form, which is to say for flowed prose that reached the page without going through
     * {@link #monospace} or {@link #label}.
     *
     * <p>Fenced markdown copies verbatim into a listing block, where backticks are inert by
     * block context rather than by span form, so the blocks {@link BlockContext} skips are
     * skipped here too.
     *
     * <p>A span crossing a line break stays a bare backtick pair, the one shape the
     * line-based converter cannot make inert, so it is not reported: only a pair opened and
     * closed on one line is a finding. {@link #pair}'s carried-open state is what tells the
     * two apart.
     */
    static List<Finding> scan(String adoc) {
        List<Finding> findings = new ArrayList<>();
        String[] lines = adoc.split("\n", -1);
        BlockContext block = new BlockContext();
        int carried = 0;
        for (int n = 0; n < lines.length; n++) {
            String line = lines[n];
            if (!block.isProse(line) || line.isBlank()) {
                carried = 0;
                continue;
            }
            Pairing p = pair(maskInert(line), carried);
            for (Span s : p.spans()) {
                findings.add(new Finding(n + 1, line.substring(s.open().start(), s.close().end())));
            }
            carried = p.openRun();
        }
        return findings;
    }
}
