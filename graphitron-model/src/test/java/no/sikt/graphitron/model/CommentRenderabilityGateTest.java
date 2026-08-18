package no.sikt.graphitron.model;

import no.sikt.graphitron.model.test.FactStores;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static no.sikt.graphitron.model.Tables.META_RELATION_FAMILY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

/**
 * The renderability gate beside the comment-coverage gate: every {@code COMMENT ON} body and
 * every meta prose value is AsciiDoc that renders as the inline subset the generated schema
 * reference accepts, and nothing in it substitutes or formats by accident.
 *
 * <p>The accepted subset is plain prose plus paired single-backtick monospace spans. Everything
 * that would go live at render is rejected in both directions: markdown-isms (bold, links, table
 * rows), which would render as literal noise, and AsciiDoc activations (attribute references,
 * bracketed inline macros, autolinking URL schemes, constrained emphasis pairs), which would
 * render as something the author did not write. Block content needs no rule because a comment is
 * one SQL literal on one physical line; the control-character rule pins that invariant from the
 * store side. The subset starts strict and widens by deliberate edit here, never by drift.
 *
 * <p>This scanner deliberately coexists with the roadmap-tool's {@code InertSpans}: the two
 * answer different questions over different source languages. {@code InertSpans} polices
 * <em>generated</em> AsciiDoc for markdown-sourced spans an emitter forgot to neutralize, so a
 * paired backtick span is exactly what it flags; here a paired backtick span is the accepted
 * subset's one formatting construct, because the comments are <em>authored</em> AsciiDoc that
 * interpolates verbatim. Neither acceptance set can be expressed in the other's terms. The
 * positive direction, that whatever this gate accepts really does render cleanly, is held by the
 * docs build itself: the generated reference lands in the site render, whose log gate fails on
 * any Asciidoctor WARN.
 */
class CommentRenderabilityGateTest {

    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^\\]]*\\]\\(");
    private static final Pattern ATTRIBUTE_REFERENCE =
        Pattern.compile("\\{[A-Za-z0-9_][A-Za-z0-9_-]*\\}");
    private static final Pattern INLINE_MACRO =
        Pattern.compile("\\b[a-z][a-z0-9+.-]*:[^\\s\\[\\]]*\\[");
    private static final Pattern AUTOLINK = Pattern.compile("\\b(?:https?|ftp|irc|mailto):\\S");
    private static final Pattern EMPHASIS_PAIR =
        Pattern.compile("(?<![\\w])([_*])(?=\\S)(?:(?!\\1).)*?(?<=\\S)\\1(?![\\w])");
    private static final Pattern EMPTY_SPAN = Pattern.compile("``");

    @Test
    @DisplayName("every relation and column comment renders in the accepted subset")
    void everyCommentRendersInTheAcceptedSubset() {
        try (var store = FactStores.inMemory()) {
            var relationComments = store.dsl()
                .select(field(name("TABLE_NAME"), String.class),
                    field(name("REMARKS"), String.class))
                .from(table(name("INFORMATION_SCHEMA", "TABLES")))
                .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .fetch();
            var columnComments = store.dsl()
                .select(field(name("TABLE_NAME"), String.class)
                        .concat(".").concat(field(name("COLUMN_NAME"), String.class)),
                    field(name("REMARKS"), String.class))
                .from(table(name("INFORMATION_SCHEMA", "COLUMNS")))
                .where(field(name("TABLE_SCHEMA"), String.class).eq("PUBLIC"))
                .fetch();

            // The floor against a vacuous pass, with no literal to fall behind: the sweep must
            // have seen exactly the census's relations, and every one of them contributes columns.
            int censusCount = store.dsl().fetchCount(META_RELATION_FAMILY);
            assertThat(relationComments).as("relation comments swept").hasSize(censusCount);
            assertThat(columnComments.stream().map(r -> r.value1().split("\\.")[0]).distinct())
                .as("relations contributing column comments")
                .hasSize(censusCount);

            var findings = new ArrayList<String>();
            relationComments.forEach(r -> findings.addAll(scan(r.value1(), r.value2())));
            columnComments.forEach(r -> findings.addAll(scan(r.value1(), r.value2())));
            assertThat(findings)
                .as("comment text outside the accepted AsciiDoc inline subset")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("every meta prose value renders in the accepted subset")
    void everyMetaProseValueRendersInTheAcceptedSubset() {
        try (var store = FactStores.inMemory()) {
            var metaRelations = store.dsl()
                .select(META_RELATION_FAMILY.RELATION_NAME)
                .from(META_RELATION_FAMILY)
                .where(META_RELATION_FAMILY.PREFIX.eq("meta_"))
                .fetch(0, String.class);
            assertThat(metaRelations).as("meta relations to sweep").isNotEmpty();

            // Total over every character-typed value of every meta_ relation, so a later prose
            // column joins the sweep by existing rather than by being remembered here.
            var findings = new ArrayList<String>();
            int swept = 0;
            for (String relation : metaRelations) {
                for (var row : store.dsl().fetch(table(name(relation.toUpperCase(Locale.ROOT))))) {
                    for (var f : row.fields()) {
                        if (row.get(f) instanceof String value) {
                            findings.addAll(scan(relation + "." + f.getName(), value));
                            swept++;
                        }
                    }
                }
            }
            assertThat(swept).as("meta prose values swept").isPositive();
            assertThat(findings)
                .as("meta prose outside the accepted AsciiDoc inline subset")
                .isEmpty();
        }
    }

    @Test
    @DisplayName("the detector pins its acceptance line in both directions")
    void theDetectorPinsItsAcceptanceLine() {
        // Accepted: plain prose, the corpus's own shapes, and paired monospace spans.
        assertThat(scan("t", "the graph''s configured name; walk_ and store_ stay apart")).isEmpty();
        assertThat(scan("t", "per the jvm_scalar_type_field precedent, since the arm lives in lint_")).isEmpty();
        assertThat(scan("t", "a `graph_name` column leads every key")).isEmpty();
        assertThat(scan("t", "quoted directive syntax: @reference(path: [{key: \"x\"}])")).isEmpty();
        assertThat(scan("t", "an unpaired opener like _this renders literally")).isEmpty();
        // Rejected: markdown-isms.
        assertThat(scan("t", "**bold** claims")).isNotEmpty();
        assertThat(scan("t", "[text](https://example.org) links")).isNotEmpty();
        assertThat(scan("t", "|---|---| separators")).isNotEmpty();
        // Rejected: AsciiDoc going live.
        assertThat(scan("t", "an {attribute} reference")).isNotEmpty();
        assertThat(scan("t", "an xref:page.adoc[live link]")).isNotEmpty();
        assertThat(scan("t", "see https://example.org for more")).isNotEmpty();
        assertThat(scan("t", "a pair of _tokens italicizing_ silently")).isNotEmpty();
        assertThat(scan("t", "a *starred pair* likewise")).isNotEmpty();
        // Rejected: span hygiene.
        assertThat(scan("t", "an `` empty span")).isNotEmpty();
        assertThat(scan("t", "an unpaired ` backtick")).isNotEmpty();
    }

    /** The acceptance line, one place: every finding names its context and quotes the offence. */
    private static List<String> scan(String context, String text) {
        var findings = new ArrayList<String>();
        if (text == null) {
            return findings;
        }
        if (text.chars().anyMatch(c -> c == '\n' || c == '\r' || c == '\t')) {
            findings.add(context + ": control characters in a one-line literal");
        }
        if (text.contains("**")) {
            findings.add(context + ": markdown bold");
        }
        if (MARKDOWN_LINK.matcher(text).find()) {
            findings.add(context + ": markdown link");
        }
        if (text.startsWith("|") || text.contains("|---")) {
            findings.add(context + ": markdown table row");
        }
        if (ATTRIBUTE_REFERENCE.matcher(text).find()) {
            findings.add(context + ": attribute reference would substitute");
        }
        if (INLINE_MACRO.matcher(text).find()) {
            findings.add(context + ": bracketed inline macro would go live");
        }
        if (AUTOLINK.matcher(text).find()) {
            findings.add(context + ": URL scheme would autolink");
        }
        var emphasis = EMPHASIS_PAIR.matcher(text);
        if (emphasis.find()) {
            findings.add(context + ": constrained emphasis pair would format: " + emphasis.group());
        }
        if (EMPTY_SPAN.matcher(text).find()) {
            findings.add(context + ": empty or unconstrained backtick span");
        }
        if (text.chars().filter(c -> c == '`').count() % 2 != 0) {
            findings.add(context + ": unpaired backtick");
        }
        return findings;
    }
}
