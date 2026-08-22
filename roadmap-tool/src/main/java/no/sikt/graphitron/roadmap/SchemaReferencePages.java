package no.sikt.graphitron.roadmap;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.catalog.GrainSentence;
import no.sikt.graphitron.model.catalog.StoreCatalog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders the fact store's schema reference: one AsciiDoc page per relation-name family plus an
 * index, generated at build and never committed, so the DDL stays the only authored source and
 * the reference cannot drift from it.
 *
 * <p>Everything structural is read from the store through {@link StoreCatalog}: the page set,
 * titles and ordering come from the {@code meta_family} rows, which also carry the introduction a
 * page opens with and the charter it closes the front matter on; what a family holds first comes
 * from {@code meta_family_headline}, how it meets the other families from
 * {@code meta_family_bridge} and {@code meta_relation_reference}, the per-object prose from the
 * {@code COMMENT ON} text, and prefix-less relations render where their
 * {@code meta_prefixless_relation} row says (an empty page means the index, the reference's one
 * cross-family surface). The renderer owns only presentation vocabulary: file names, heading
 * levels, the fixed template phrases. Even the one-line summary beside a headline is the store's
 * convention rather than the renderer's, lifted by {@link GrainSentence}, which is why that
 * extractor lives beside the catalog reader and not here. Comment and meta prose interpolates verbatim as AsciiDoc
 * (the renderability gate beside the store's comment-coverage gate holds the accepted subset);
 * spans this class mints itself go out inert via {@link InertSpans}, the module's own line
 * against accidental substitution.
 *
 * <p>Generated-not-committed removes the drift signal the committed fragments get from their
 * verify diff, so the floors here fail the build instead: an empty catalog, a relation on no
 * page or more than one, or a rendered relation or column with blank comment text all throw
 * {@link BuildFailure} rather than rendering a plausible empty reference.
 */
final class SchemaReferencePages {

    private SchemaReferencePages() {}

    /** CLI: {@code render-schema-reference <output-dir>}. Boots a fresh store and renders. */
    static int run(List<String> args) throws IOException {
        if (args.size() != 1) {
            System.err.println("usage: render-schema-reference <output-dir>");
            return 64;
        }
        StoreCatalog catalog;
        try (var store = GraphitronModelStore.open()) {
            catalog = StoreCatalog.read(store.dsl());
        }
        Path out = Path.of(args.get(0)).toAbsolutePath().normalize();
        Map<String, String> pages = render(catalog);
        Files.createDirectories(out);
        for (var page : pages.entrySet()) {
            Files.writeString(out.resolve(page.getKey()), page.getValue());
        }
        System.out.println("render-schema-reference: " + pages.size() + " pages ("
            + catalog.relations().size() + " relations) into " + out);
        return 0;
    }

    /** Renders the whole reference as file name to content, floors included. */
    static Map<String, String> render(StoreCatalog catalog) {
        if (catalog.families().isEmpty() || catalog.relations().isEmpty()) {
            throw new BuildFailure("the schema reference rendered from an empty catalog;"
                + " the store bootstrap or the meta_family roster is broken");
        }
        var blankComments = new ArrayList<String>();
        for (var relation : catalog.relations()) {
            if (isBlank(relation.comment())) {
                blankComments.add(relation.relationName());
            }
            for (var column : relation.columns()) {
                if (isBlank(column.comment())) {
                    blankComments.add(relation.relationName() + "." + column.columnName());
                }
            }
        }
        if (!blankComments.isEmpty()) {
            throw new BuildFailure("relations or columns with blank comment text would render"
                + " an empty reference entry: " + String.join(", ", blankComments));
        }

        Map<String, String> pageByRelation = assignPages(catalog);
        checkFamilyOpenings(catalog, pageByRelation);

        var pages = new LinkedHashMap<String, String>();
        for (var family : catalog.families()) {
            pages.put(pageFile(family.prefix()), familyPage(catalog, family, pageByRelation));
        }
        pages.put("index.adoc", indexPage(catalog, pageByRelation));
        return pages;
    }

    /**
     * The floors on what a family page opens with. Generated-not-committed means a family whose
     * introduction never got authored, or whose headline roster was dropped, would render a page
     * that opens on nothing and looks deliberate; the store's roster gates hold the live DDL to
     * both, and these hold the renderer to them for any catalog it is handed.
     */
    private static void checkFamilyOpenings(StoreCatalog catalog,
                                            Map<String, String> pageByRelation) {
        var openings = new ArrayList<String>();
        for (var family : catalog.families()) {
            if (isBlank(family.introduction())) {
                openings.add(family.prefix() + " has no introduction");
            }
            if (headlinesOf(catalog, family).isEmpty()) {
                openings.add(family.prefix() + " has no headline relations");
            }
        }
        for (var headline : catalog.headlines()) {
            if (!pageByRelation.containsKey(headline.relationName())) {
                openings.add(headline.relationName() + " is a headline of "
                    + headline.familyPrefix() + " but renders on no page");
            }
        }
        if (!openings.isEmpty()) {
            throw new BuildFailure("families whose page would open on nothing: "
                + String.join(", ", openings));
        }
    }

    /**
     * Every relation to the file that renders it, exactly one each: the family page by census
     * prefix, the exemption row's page for the prefix-less, the index where that page is empty.
     */
    private static Map<String, String> assignPages(StoreCatalog catalog) {
        var familyPrefixes = catalog.families().stream().map(StoreCatalog.Family::prefix).toList();
        var assignment = new LinkedHashMap<String, String>();
        var unassigned = new ArrayList<String>();
        for (var relation : catalog.relations()) {
            if (relation.familyPrefix().isPresent() && !relation.exempted()) {
                assignment.put(relation.relationName(), pageFile(relation.familyPrefix().get()));
                continue;
            }
            var exemption = catalog.exemptions().stream()
                .filter(x -> x.relationName().equals(relation.relationName()))
                .findFirst();
            if (relation.familyPrefix().isEmpty() && exemption.isPresent()) {
                String page = exemption.get().page()
                    .map(prefix -> {
                        if (!familyPrefixes.contains(prefix)) {
                            throw new BuildFailure("exemption row for "
                                + relation.relationName() + " names a page that is no family: "
                                + prefix);
                        }
                        return pageFile(prefix);
                    })
                    .orElse("index.adoc");
                assignment.put(relation.relationName(), page);
                continue;
            }
            unassigned.add(relation.relationName());
        }
        if (!unassigned.isEmpty()) {
            throw new BuildFailure("relations with no documentation page; the census and the"
                + " exemption rows disagree: " + String.join(", ", unassigned));
        }
        return assignment;
    }

    private static String familyPage(StoreCatalog catalog, StoreCatalog.Family family,
                                     Map<String, String> pageByRelation) {
        var page = new StringBuilder();
        header(page, family.title(), "The " + family.prefix()
            + " family of the graphitron fact store, generated from the DDL's own comments.");
        page.append("Prefix: ").append(InertSpans.monospace(family.prefix())).append(".\n\n");
        page.append(family.introduction()).append("\n");
        whereToStart(page, catalog, family, pageByRelation);
        howThisFamilyMeetsTheOthers(page, catalog, family, pageByRelation);
        page.append("\n== Why the name is right\n\n");
        page.append(family.definition()).append("\n");

        String file = pageFile(family.prefix());
        for (var relation : catalog.relations()) {
            if (file.equals(pageByRelation.get(relation.relationName()))) {
                renderRelation(page, relation, "==", pageByRelation, catalog);
            }
        }
        page.append("\nxref:index.adoc[← Schema index]\n");
        return page.toString();
    }

    /**
     * The family's headline roster: where a reader should start, each entry linked to its own
     * entry further down the page and carrying the grain sentence lifted from that entry's
     * comment. Nothing here is authored twice; the roster names relations and
     * {@link GrainSentence} says what each one is, out of the relation's own prose.
     */
    private static void whereToStart(StringBuilder page, StoreCatalog catalog,
                                     StoreCatalog.Family family,
                                     Map<String, String> pageByRelation) {
        page.append("\n== Where to start\n\n");
        for (var headline : headlinesOf(catalog, family)) {
            page.append(relationLink(headline.relationName(), pageByRelation)).append("::\n")
                .append(GrainSentence.of(commentOf(catalog, headline.relationName())))
                .append("\n");
        }
    }

    /**
     * How the family's rows reach other families' rows, in the two provenances the store keeps
     * apart. The declared crossings are authored rows saying which relation owns a normalization
     * rule; the key edges are the engine's own foreign keys, aggregated per family. The wording
     * presents the crossings as declarations and claims no exhaustiveness, because the store
     * declares them and nothing yet closes them against what the view definitions do.
     */
    private static void howThisFamilyMeetsTheOthers(StringBuilder page, StoreCatalog catalog,
                                                    StoreCatalog.Family family,
                                                    Map<String, String> pageByRelation) {
        page.append("\n== How this family meets the others\n\n");
        var crossings = catalog.bridges().stream()
            .filter(bridge -> bridge.spelledPrefix().equals(family.prefix())
                || bridge.censusPrefix().equals(family.prefix()))
            .toList();
        var edges = keyEdgesByOtherFamily(catalog, family);
        if (crossings.isEmpty() && edges.isEmpty()) {
            page.append("No crossing is declared for this family and no foreign key crosses its"
                + " boundary; its rows meet the other families' only where a reader joins"
                + " them.\n");
            return;
        }

        if (!crossings.isEmpty()) {
            page.append("The normalization crossings declared for this family: rules by which a"
                + " name written in one family's vocabulary is matched against another's census."
                + " Declared rows rather than a closed set, so a crossing nobody has declared"
                + " does not appear here.\n\n");
            page.append(".Declared crossings\n");
            for (var bridge : crossings) {
                page.append(relationLink(bridge.relationName(), pageByRelation)).append(" (")
                    .append(InertSpans.monospace(bridge.spelledPrefix()))
                    .append(" spellings against the ")
                    .append(InertSpans.monospace(bridge.censusPrefix()))
                    .append(" census)::\n").append(bridge.rule()).append("\n");
            }
            page.append("\n");
        }

        if (!edges.isEmpty()) {
            page.append(".Declared key edges\n");
            edges.forEach((prefix, edge) -> page.append(InertSpans.monospace(prefix))
                .append("::\n").append(edge).append("\n"));
        }
    }

    /**
     * The declared foreign keys crossing this family's boundary, one sentence per family on the
     * other end, in roster order. Edges inside the family are left out: the section is about how
     * this family meets the others, and its own internal shape is what the relation entries say.
     */
    private static Map<String, String> keyEdgesByOtherFamily(StoreCatalog catalog,
                                                             StoreCatalog.Family family) {
        var outgoing = new LinkedHashMap<String, Integer>();
        var incoming = new LinkedHashMap<String, Integer>();
        for (var reference : catalog.references()) {
            String child = reference.childPrefix().orElse(null);
            String parent = reference.parentPrefix().orElse(null);
            if (child == null || parent == null || child.equals(parent)) {
                continue;
            }
            if (child.equals(family.prefix())) {
                outgoing.merge(parent, 1, Integer::sum);
            } else if (parent.equals(family.prefix())) {
                incoming.merge(child, 1, Integer::sum);
            }
        }
        var edges = new LinkedHashMap<String, String>();
        for (var other : catalog.families()) {
            int out = outgoing.getOrDefault(other.prefix(), 0);
            int in = incoming.getOrDefault(other.prefix(), 0);
            if (out == 0 && in == 0) {
                continue;
            }
            var sentence = new StringBuilder();
            if (out > 0) {
                sentence.append(foreignKeyCount(out)).append(" from this family's rows into that"
                    + " one");
            }
            if (in > 0) {
                sentence.append(out > 0 ? ", and " : "").append(foreignKeyCount(in))
                    .append(out > 0 ? " the other way" : " from that family's rows into this one");
            }
            edges.put(other.prefix(), sentence.append(".").toString());
        }
        return edges;
    }

    private static String foreignKeyCount(int count) {
        return count + (count == 1 ? " foreign key" : " foreign keys");
    }

    private static List<StoreCatalog.Headline> headlinesOf(StoreCatalog catalog,
                                                           StoreCatalog.Family family) {
        return catalog.headlines().stream()
            .filter(headline -> family.prefix().equals(headline.familyPrefix()))
            .toList();
    }

    private static String commentOf(StoreCatalog catalog, String relationName) {
        return catalog.relations().stream()
            .filter(relation -> relation.relationName().equals(relationName))
            .findFirst()
            .map(StoreCatalog.Relation::comment)
            .orElse("");
    }

    private static String indexPage(StoreCatalog catalog, Map<String, String> pageByRelation) {
        var page = new StringBuilder();
        header(page, "The fact store schema",
            "The graphitron fact store's relations, one page per family, generated from the"
                + " DDL's own comments.");
        page.append("One page per relation-name family, every page generated from the DDL"
            + " (graphitron-model.sql): the family roster, its introductions and charters, the"
            + " headline relations and the declared crossings from the meta_ rows, the"
            + " per-object prose from the COMMENT ON text. The DDL is the only"
            + " authored source; where prose elsewhere disagrees with it, the DDL wins.\n");

        page.append("\n== Families\n\n");
        for (var family : catalog.families()) {
            long count = catalog.relations().stream()
                .filter(r -> r.familyPrefix().map(family.prefix()::equals).orElse(false))
                .count();
            page.append(InertSpans.monospace(family.prefix())).append(" xref:")
                .append(pageFile(family.prefix())).append("[").append(family.title())
                .append("] (").append(count).append(count == 1 ? " relation" : " relations")
                .append(")::\n").append(family.introduction()).append("\n\n");
        }

        var indexed = catalog.relations().stream()
            .filter(r -> "index.adoc".equals(pageByRelation.get(r.relationName())))
            .toList();
        if (!indexed.isEmpty()) {
            page.append("== Outside every family\n");
            for (var relation : indexed) {
                catalog.exemptions().stream()
                    .filter(x -> x.relationName().equals(relation.relationName()))
                    .findFirst()
                    .ifPresent(x -> page.append("\n").append(x.reason()).append("\n"));
                renderRelation(page, relation, "===", pageByRelation, catalog);
            }
        }
        page.append("\nxref:../index.adoc[← Reference]\n");
        return page.toString();
    }

    private static void header(StringBuilder page, String title, String description) {
        page.append("// Generated by graphitron-roadmap-tool render-schema-reference from the"
            + " booted fact store. Never edit by hand; edit the DDL's COMMENT ON text or the"
            + " meta_ rows instead.\n");
        page.append("= ").append(title).append("\n");
        page.append(":description: ").append(description).append("\n");
        page.append(":!toc:\n\n");
    }

    private static void renderRelation(StringBuilder page, StoreCatalog.Relation relation,
                                       String heading, Map<String, String> pageByRelation,
                                       StoreCatalog catalog) {
        page.append("\n[#").append(relation.relationName()).append("]\n");
        page.append(heading).append(" ").append(relation.relationName())
            .append(relation.view() ? " (view)" : "").append("\n\n");
        page.append(relation.comment()).append("\n\n");

        if (!relation.primaryKey().isEmpty()) {
            page.append("Primary key: ").append(columnTuple(relation.primaryKey())).append(".");
            for (var foreignKey : relation.foreignKeys()) {
                page.append(" Foreign key: ").append(columnTuple(foreignKey.columns()))
                    .append(" references ")
                    .append(relationLink(foreignKey.referencedRelation(), pageByRelation))
                    .append(" ").append(columnTuple(foreignKey.referencedColumns())).append(".");
            }
            for (var check : relation.checks()) {
                page.append(" Check: ").append(InertSpans.monospace(check.clause())).append(".");
            }
            page.append("\n\n");
        }

        page.append(".Columns\n");
        for (var column : relation.columns()) {
            page.append(InertSpans.monospace(column.columnName())).append(" (")
                .append(column.type()).append(column.nullable() ? ", nullable" : ", not null")
                .append(")::\n").append(column.comment()).append("\n");
        }
    }

    private static String relationLink(String relation, Map<String, String> pageByRelation) {
        return "xref:" + pageByRelation.get(relation) + "#" + relation + "[" + relation + "]";
    }

    private static String columnTuple(List<String> columns) {
        return InertSpans.monospace("(" + String.join(", ", columns) + ")");
    }

    private static String pageFile(String prefix) {
        String base = prefix.endsWith("_") ? prefix.substring(0, prefix.length() - 1) : prefix;
        return base + ".adoc";
    }

    private static boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
