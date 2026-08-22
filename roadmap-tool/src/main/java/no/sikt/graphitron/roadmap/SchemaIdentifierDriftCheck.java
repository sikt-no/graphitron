package no.sikt.graphitron.roadmap;

import no.sikt.graphitron.model.boot.GraphitronModelStore;
import no.sikt.graphitron.model.catalog.StoreCatalog;
import no.sikt.graphitron.model.catalog.StoreProse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Fails the build when an authored architecture page names a store relation, column or family
 * that the store no longer declares. The explanation pages cite the fact schema in prose, and
 * those citations drift silently: renaming a relation changes nothing any build step read until
 * this check existed, which is exactly how the old pipeline overview came to describe a retired
 * architecture as current.
 *
 * <p>The universe of valid names comes from the booted store through {@link StoreCatalog}, the
 * same reader the generated schema reference renders from, never from regexing the DDL: if the
 * guard parsed the {@code .sql} itself, two mechanisms of different fidelity would answer "what
 * relations exist". An identifier is in scope when it starts with an observed family prefix; it
 * resolves as a family prefix, a relation name, or a {@code relation.column} pair. Identifiers
 * outside every family (ordinary code words, module names, bare column names) are not the
 * store's to police and are ignored.
 *
 * <p>Two corpora, one universe and one resolver. The authored architecture pages cite the schema
 * in delimited backtick spans, so {@link #scan} looks for spans. The store's own prose cites it
 * in bare running text, and cites it constantly: a relation comment explaining why a fact lives
 * where it does names its neighbours, a family charter names the families it is not, and a
 * declared crossing names the rule's owner by construction. Those citations drift the same way
 * the pages' do and had nothing watching them, so {@link #scanStoreProse} sweeps them with its own
 * extractor over prefix-anchored bare tokens. Rejected names in the charters ({@code jooq_},
 * {@code extension_}, {@code validator_}) are not a hazard: an unobserved prefix is out of scope
 * by the same filter that keeps ordinary words out.
 */
final class SchemaIdentifierDriftCheck {

    /**
     * The scanned habitat, relative to the repo root. The authored pages only: the generated
     * schema reference is correct by construction and never committed, so it has nothing to
     * drift. The directory must exist and contain pages; a scan that reaches nothing fails
     * rather than passing vacuously.
     */
    static final String SCANNED_TREE = "docs/architecture";

    /** What a store-prose finding names instead of a page path: the DDL is one file. */
    static final String STORE_CORPUS =
        "graphitron-model/src/main/resources/no/sikt/graphitron/model/graphitron-model.sql";

    /** A backtick span on one line; the inert plus form is unwrapped before matching. */
    private static final Pattern SPAN = Pattern.compile("`([^`]+)`");

    /** The shape of a store identifier: one token, optionally qualified by one column. */
    private static final Pattern IDENTIFIER =
        Pattern.compile("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)?");

    /**
     * The same shape found loose in running text. The leading guard refuses a start inside a
     * longer word or after a dot, so a package-qualified name contributes its own first segment
     * and never one of its tails; the trailing guard refuses a match that stops mid-word. A
     * sentence-ending dot is not a word character, so a relation named at the end of a sentence
     * is still seen.
     */
    private static final Pattern BARE_IDENTIFIER =
        Pattern.compile("(?<![\\w.])[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)?(?![\\w])");

    /** The store-metadata half of the reader, reduced to what resolution needs. */
    record Universe(Set<String> prefixes, Map<String, Set<String>> columnsByRelation) {}

    record Finding(String file, int line, String identifier) {}

    private SchemaIdentifierDriftCheck() {}

    /**
     * Entry point invoked by {@link Main}. Takes one argument, the repository root holding
     * {@link #SCANNED_TREE}. Returns 0 when every in-scope identifier resolves, 64 on usage or
     * non-directory-root errors; throws {@link BuildFailure} on findings, a missing tree, or a
     * store that parses to nothing.
     */
    static int run(List<String> args) throws IOException {
        if (args.size() != 1) {
            System.err.println("usage: check-schema-identifiers <repo-root>");
            return 64;
        }
        Path root = Path.of(args.get(0)).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("not a directory: " + root);
            return 64;
        }
        Path tree = root.resolve(SCANNED_TREE);
        if (!Files.isDirectory(tree)) {
            System.err.println("check-schema-identifiers: no " + SCANNED_TREE + " under " + root
                + "; a scan that silently reaches nothing would pass vacuously.");
            throw new BuildFailure("authored architecture tree not found");
        }

        Universe universe;
        List<StoreProse.Entry> prose;
        try (var store = GraphitronModelStore.open()) {
            universe = universeOf(StoreCatalog.read(store.dsl()));
            prose = StoreProse.read(store.dsl());
        }
        if (universe.prefixes().isEmpty() || universe.columnsByRelation().isEmpty()) {
            System.err.println("check-schema-identifiers: the booted store yielded no families or"
                + " no relations; the reader is broken and every citation would pass vacuously.");
            throw new BuildFailure("store catalog parsed to nothing");
        }

        List<Path> pages;
        try (Stream<Path> walk = Files.walk(tree)) {
            pages = walk.filter(p -> p.toString().endsWith(".adoc")).sorted().toList();
        }
        if (pages.isEmpty()) {
            System.err.println("check-schema-identifiers: no .adoc pages under " + tree
                + "; a scan that silently reaches nothing would pass vacuously.");
            throw new BuildFailure("authored architecture tree holds no pages");
        }

        if (prose.isEmpty()) {
            System.err.println("check-schema-identifiers: the booted store yielded no prose;"
                + " the store corpus would pass vacuously.");
            throw new BuildFailure("store prose read as nothing");
        }

        var findings = new ArrayList<Finding>();
        for (Path page : pages) {
            findings.addAll(scan(root.relativize(page).toString(),
                Files.readString(page), universe));
        }
        findings.addAll(scanStoreProse(prose, universe));

        if (findings.isEmpty()) {
            System.out.println("check-schema-identifiers: " + pages.size() + " pages and "
                + prose.size() + " store prose values resolve against "
                + universe.columnsByRelation().size() + " relations in "
                + universe.prefixes().size() + " families.");
            return 0;
        }
        System.err.println("check-schema-identifiers: " + findings.size() + " identifier(s) the"
            + " store does not declare. Rename the citation to the live relation, or drop it;"
            + " the store's DDL is the model of record and the prose beside it must follow it.");
        for (Finding finding : findings) {
            System.err.println("  " + finding.file() + ":" + finding.line() + ": `"
                + finding.identifier() + "`");
        }
        throw new BuildFailure("citations name store identifiers the schema does not declare");
    }

    /** Reduces the catalog to the resolution universe; the meta rows contribute like any other. */
    static Universe universeOf(StoreCatalog catalog) {
        var prefixes = new LinkedHashSet<String>();
        for (var family : catalog.families()) {
            prefixes.add(family.prefix());
        }
        var columns = new LinkedHashMap<String, Set<String>>();
        for (var relation : catalog.relations()) {
            var names = new LinkedHashSet<String>();
            for (var column : relation.columns()) {
                names.add(column.columnName());
            }
            columns.put(relation.relationName(), names);
        }
        return new Universe(prefixes, columns);
    }

    /**
     * Scans one page: backtick spans in prose lines (verbatim blocks per the shared
     * {@link InertSpans.BlockContext}), unwrapped from the inert plus form where present,
     * kept when they are a single identifier starting with an observed family prefix.
     */
    static List<Finding> scan(String file, String adoc, Universe universe) {
        var findings = new ArrayList<Finding>();
        var block = new InertSpans.BlockContext();
        String[] lines = adoc.split("\n", -1);
        for (int n = 0; n < lines.length; n++) {
            if (!block.isProse(lines[n])) {
                continue;
            }
            var span = SPAN.matcher(lines[n]);
            while (span.find()) {
                String content = span.group(1);
                if (content.length() > 2 && content.startsWith("+") && content.endsWith("+")) {
                    content = content.substring(1, content.length() - 1);
                }
                if (!IDENTIFIER.matcher(content).matches()) {
                    continue;
                }
                String relationPart = content.contains(".")
                    ? content.substring(0, content.indexOf('.')) : content;
                if (universe.prefixes().stream().noneMatch(relationPart::startsWith)) {
                    continue;
                }
                if (!resolves(content, relationPart, universe)) {
                    findings.add(new Finding(file, n + 1, content));
                }
            }
        }
        return findings;
    }

    /**
     * Scans the store's own prose: bare prefix-anchored tokens, resolved against the same
     * universe the pages are. A finding names the prose value's context in the file position's
     * place, since a comment has no line of its own once the engine holds it; the DDL is one
     * file and the context is what a reader searches it by.
     */
    static List<Finding> scanStoreProse(List<StoreProse.Entry> prose, Universe universe) {
        var findings = new ArrayList<Finding>();
        for (var entry : prose) {
            if (entry.text() == null) {
                continue;
            }
            var token = BARE_IDENTIFIER.matcher(entry.text());
            while (token.find()) {
                String content = token.group();
                String relationPart = content.contains(".")
                    ? content.substring(0, content.indexOf('.')) : content;
                if (universe.prefixes().stream().noneMatch(relationPart::startsWith)) {
                    continue;
                }
                if (!resolves(content, relationPart, universe)) {
                    findings.add(new Finding(STORE_CORPUS, 0, entry.context() + ": " + content));
                }
            }
        }
        return findings;
    }

    private static boolean resolves(String identifier, String relationPart, Universe universe) {
        if (universe.prefixes().contains(identifier)) {
            return true;
        }
        Set<String> columns = universe.columnsByRelation().get(relationPart);
        if (columns == null) {
            return false;
        }
        return relationPart.equals(identifier)
            || columns.contains(identifier.substring(identifier.indexOf('.') + 1));
    }
}
