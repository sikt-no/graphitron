package no.sikt.graphitron.model.reference;

import no.sikt.graphitron.model.boot.DefectGuidance;
import no.sikt.graphitron.model.boot.GraphitronModelStore;
import org.jooq.DSLContext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFECT_GUIDANCE;
import static no.sikt.graphitron.model.Tables.GRAPHITRON_DEFECT_TYPE;

/**
 * Renders the defects reference page of the user manual from a booted store: one anchored entry
 * per code {@code graphitron_defect_type} declares, carrying its statement, severity, basis and
 * editor code, and the fix guidance {@code graphitron_defect_guidance} holds for it. Generated at
 * the docs build and never committed, so the store stays the only source and the page cannot
 * drift from it.
 *
 * <p>Generated-not-committed has no diff to show drift, so the floors here fail the build
 * instead of rendering a plausible page: no declared codes, a blank statement, a statement that
 * cannot be held inert, an {@code UNSUPPORTED} code with no guidance, and a guidance fragment
 * naming a code the store does not declare. A link into the page naming a code no longer declared
 * is the docs build's own link check, which runs over the rendered page.
 *
 * <p>An entry's anchor is its code in lower kebab case, {@code MUTATION_UPSERT} at
 * {@code #mutation-upsert}, which is what {@link #anchor} computes and what the editor and the MCP
 * server link to.
 */
public final class DefectReferencePage {

    /** The published page an entry's link names, its anchor appended. */
    public static final String PAGE_URL = "https://graphitron.sikt.no/manual/reference/defects.html";

    private static final List<String> BASIS_ORDER = List.of("SPECIFICATION", "GRAPHITRON", "UNSUPPORTED");

    private DefectReferencePage() {}

    /** One declared code as the page renders it; {@code guidance} is null where none is written. */
    public record Entry(String code, String severity, String basis, String lspCode, String statement,
                        String guidance) {}

    /** CLI: {@code <output-file>}. Boots a fresh store, renders, and writes the page. */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: DefectReferencePage <output-file>");
        }
        List<Entry> entries;
        try (var store = GraphitronModelStore.open()) {
            entries = read(store.dsl());
        }
        String page = render(entries, DefectGuidance.fragmentCodes());
        Path out = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(out.getParent());
        Files.writeString(out, page, StandardCharsets.UTF_8);
        System.out.println("render-defects-reference: " + entries.size() + " codes into " + out);
    }

    /** Every declared code with its guidance, by code. */
    public static List<Entry> read(DSLContext dsl) {
        var t = GRAPHITRON_DEFECT_TYPE;
        var g = GRAPHITRON_DEFECT_GUIDANCE;
        return dsl.select(t.CODE, t.SEVERITY, t.BASIS, t.LSP_CODE, t.STATEMENT, g.GUIDANCE)
            .from(t)
            .leftJoin(g).on(g.CODE.eq(t.CODE))
            .orderBy(t.CODE)
            .fetch(r -> new Entry(r.value1(), r.value2(), r.value3(), r.value4(), r.value5(), r.value6()));
    }

    /** The anchor of {@code code}'s entry: the code in lower kebab case. */
    public static String anchor(String code) {
        return code.toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /** The published link to {@code code}'s entry. */
    public static String link(String code) {
        return PAGE_URL + "#" + anchor(code);
    }

    /**
     * Renders the page, floors included.
     *
     * @param fragmentCodes the codes the guidance fragment directory names, held against the
     *        declared codes so an orphaned fragment fails here as well as in the model's tests
     * @throws IllegalStateException on any floor
     */
    public static String render(List<Entry> entries, Set<String> fragmentCodes) {
        if (entries.isEmpty()) {
            throw new IllegalStateException("the defects reference rendered from no declared codes;"
                + " the store bootstrap or graphitron_defect_type is broken");
        }
        var faults = new ArrayList<String>();
        var declared = new TreeSet<String>();
        for (var entry : entries) {
            declared.add(entry.code());
            if (entry.statement() == null || entry.statement().isBlank()) {
                faults.add(entry.code() + " has a blank statement");
            } else if (entry.statement().contains("+") || entry.statement().contains("\n")) {
                faults.add(entry.code() + "'s statement holds a '+' or a line break, which the page"
                    + " cannot hold inert");
            }
            if ("UNSUPPORTED".equals(entry.basis()) && entry.guidance() == null) {
                faults.add(entry.code() + " is UNSUPPORTED and has no guidance fragment under "
                    + DefectGuidance.DIRECTORY);
            }
            if (!BASIS_ORDER.contains(entry.basis())) {
                faults.add(entry.code() + " has a basis the page has no section for: " + entry.basis());
            }
        }
        var orphans = new TreeSet<>(fragmentCodes);
        orphans.removeAll(declared);
        for (String orphan : orphans) {
            faults.add("the guidance fragment " + orphan + ".adoc names a code the store does not declare");
        }
        if (!faults.isEmpty()) {
            throw new IllegalStateException("the defects reference cannot render: "
                + String.join("; ", faults));
        }

        var page = new StringBuilder();
        page.append("// Generated by graphitron-model's DefectReferencePage from the booted fact store."
            + " Never edit by hand; edit graphitron_defect_type's rows or the guidance fragments under "
            + DefectGuidance.DIRECTORY + " instead.\n");
        page.append(INTRODUCTION);
        for (String basis : BASIS_ORDER) {
            var section = entries.stream().filter(e -> e.basis().equals(basis)).toList();
            if (section.isEmpty()) {
                continue;
            }
            page.append("\n[#").append(sectionAnchor(basis)).append("]\n== ").append(sectionTitle(basis)).append("\n\n")
                .append(sectionIntroduction(basis)).append("\n");
            for (var entry : section) {
                renderEntry(page, entry);
            }
        }
        page.append("\nxref:index.adoc[← Reference index]\n");
        return page.toString();
    }

    private static void renderEntry(StringBuilder page, Entry entry) {
        page.append("\n[#").append(anchor(entry.code())).append("]\n");
        page.append("=== `").append(entry.code()).append("`\n\n");
        page.append("+").append(entry.statement().strip()).append("+\n\n");
        page.append("[horizontal]\n");
        page.append("Severity:: ").append(entry.severity()).append("\n");
        page.append("Basis:: `").append(entry.basis()).append("`\n");
        if (entry.lspCode() != null) {
            page.append("Editor code:: `").append(entry.lspCode()).append("`\n");
        }
        if (entry.guidance() != null) {
            page.append("\n").append(entry.guidance().strip()).append("\n");
        }
    }

    /** The anchor of a basis's section; {@code #deferrals} is the one other pages link to. */
    private static String sectionAnchor(String basis) {
        return switch (basis) {
            case "SPECIFICATION" -> "specification-defects";
            case "GRAPHITRON" -> "graphitron-defects";
            case "UNSUPPORTED" -> "deferrals";
            default -> throw new IllegalStateException(basis);
        };
    }

    private static String sectionTitle(String basis) {
        return switch (basis) {
            case "SPECIFICATION" -> "Forbidden by the GraphQL specification";
            case "GRAPHITRON" -> "Errors in graphitron's directives";
            case "UNSUPPORTED" -> "Deferrals: valid, not generated yet";
            default -> throw new IllegalStateException(basis);
        };
    }

    private static String sectionIntroduction(String basis) {
        return switch (basis) {
            case "SPECIFICATION" -> "The GraphQL specification forbids the schema; change it.";
            case "GRAPHITRON" -> "Graphitron's directives give the schema no meaning as written."
                + " Changing the schema removes the defect.";
            case "UNSUPPORTED" -> "The schema is valid and means something, and graphitron does not"
                + " generate it yet. Each entry says what is valid, why it is not generated, and what"
                + " to write instead.";
            default -> throw new IllegalStateException(basis);
        };
    }

    private static final String INTRODUCTION = """
        = Defects reference
        :description: Every defect graphitron reports on a schema, by code: the rule, how bad it is, what it rests on, and how to fix it or what to write instead.

        A defect is a rule a schema breaks, reported at the place in the schema the author edits to \
        fix it. The build, the editor and the MCP server read the same defects, so a code you meet \
        in one is the code you meet in the others, and this page is where each code is explained.

        == Reading an entry

        Each entry is headed by its code, the name the build's message and the MCP server give it.

        * The first paragraph is the rule, stated once. A message quotes it, followed by a detail in \
        parentheses naming what this occurrence is about: a table, a type, a parameter.
        * *Severity* says whether the build stops. An `error` stops `validate` and `generate` before \
        anything is generated, and `dev` reports it and keeps running. A `warning` is shown and \
        logged and stops nothing.
        * *Basis* says what the verdict rests on, and so what you do about it: change the schema \
        for `SPECIFICATION` and `GRAPHITRON`, or work around graphitron for `UNSUPPORTED`.
        * *Editor code* is the code the editor shows beside the message and filters by.
        * What follows, where an entry has it, is the fix guidance: what reaches the defect, and what \
        to write instead.

        A deferral, a defect whose basis is `UNSUPPORTED`, is a schema graphitron recognises and does \
        not generate yet. The schema is not wrong, so the editor and the MCP server mark it as not \
        actionable, and every deferral says what to write instead. Either change the shape to what \
        its entry suggests, or keep it and revisit it when graphitron learns it; a deferral goes \
        away, with its code, when the shape is generated.

        The classification walk's own rejections, the `[author-error]`, `[invalid-schema]` and \
        `[deferred]` lines that carry no code from this page, are described in the \
        xref:diagnostics-glossary.adoc[diagnostics glossary].
        """;
}
