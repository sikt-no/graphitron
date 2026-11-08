package no.sikt.graphitron.rewrite.classifieddsl;

import graphql.language.Argument;
import graphql.language.Directive;
import graphql.language.DirectivesContainer;
import graphql.language.StringValue;
import graphql.parser.Parser;
import no.sikt.graphitron.model.test.CorpusDocuments.Document;
import no.sikt.graphitron.model.test.CorpusExpectations;
import no.sikt.graphitron.model.test.CorpusExpectations.Block;
import no.sikt.graphitron.model.test.FactDocuments;
import no.sikt.graphitron.model.test.Scenario;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A documentation page's schema example, rendered from a query over a fact document rather than
 * written by hand, so an example cannot show what the store does not hold.
 *
 * <p>A page's examples are files in an {@code examples} folder beside it, named for the page: a
 * {@code .graphql} file holding one named operation or fragment per example, each marked
 * {@code @factDocument(path:)} naming the fact document it selects from as its folder and name
 * ({@code scenarios/result-external-field}), and the {@code .graphqls} file holding what
 * {@link QueryViewRenderer} renders from each selection over its document, its real directives kept
 * and the fact documents' own stripped. Each example renders into a tag region named for its
 * definition, which the page includes where the example belongs, leaving the header out.
 *
 * <p>An example may also mark {@code @factRows(relation:)}, once per relation, to show the rows its
 * fact document asserts for that relation. Those render as tables into the page's {@code _}-prefixed
 * {@code .adoc} beside the other two, a region each named for the example and the relation. The rows
 * are the document's own assertion, which {@code FactExpectationTest} holds to the store.
 *
 * <p>The rendered files are committed so that an {@code include::} resolves in every build, including the ones that skip tests and
 * could never have rendered it, and {@link ManualExampleTest} holds the committed files to the render.
 */
final class ManualExamples {

    /**
     * One example: the definition's name, which is its tag region, the fact document, the selection,
     * and the relations whose asserted rows it shows.
     */
    record Example(String name, String path, String selection, List<String> relations) {}

    private static final String QUERY_SUFFIX = ".graphql";
    private static final String RENDERED_SUFFIX = ".graphqls";
    private static final String ROWS_PREFIX = "_";
    private static final String ROWS_SUFFIX = ".adoc";
    private static final String FOLDER = "examples";
    private static final String DIRECTIVE = "factDocument";
    private static final String ROWS_DIRECTIVE = "factRows";

    /** The documentation root, resolved from either the reactor root or a module directory. */
    private static final List<Path> ROOT_CANDIDATES = List.of(Path.of("..", "docs"), Path.of("docs"));

    private ManualExamples() {}

    static Path docs() {
        return ROOT_CANDIDATES.stream().filter(Files::isDirectory).findFirst()
            .orElseThrow(() -> new IllegalStateException("no docs under " + ROOT_CANDIDATES));
    }

    /** Every example query under the manual, sorted by path. */
    static List<Path> queries() {
        return files(QUERY_SUFFIX);
    }

    /** Every rendered example under the manual, sorted by path. */
    static List<Path> rendered() {
        return files(RENDERED_SUFFIX);
    }

    /** The rendered file a query writes, beside it. */
    static Path renderedFile(Path query) {
        return query.resolveSibling(baseName(query) + RENDERED_SUFFIX);
    }

    /** Every rendered table file under the documentation, sorted by path. */
    static List<Path> renderedRows() {
        return files(ROWS_SUFFIX).stream()
            .filter(p -> p.getFileName().toString().startsWith(ROWS_PREFIX))
            .toList();
    }

    /** The query a rendered file is written from, beside it. */
    static Path queryFile(Path rendered) {
        String base = baseName(rendered);
        if (rendered.getFileName().toString().endsWith(ROWS_SUFFIX)) {
            base = base.substring(ROWS_PREFIX.length());
        }
        return rendered.resolveSibling(base + QUERY_SUFFIX);
    }

    /** The table file a query writes its examples' rows to, beside it. */
    static Path rowsFile(Path query) {
        return query.resolveSibling(ROWS_PREFIX + baseName(query) + ROWS_SUFFIX);
    }

    /** The line a page beside the {@code examples} folder includes one example's region with. */
    static String includeLine(Path rendered, String example) {
        return "include::" + FOLDER + "/" + rendered.getFileName() + "[tag=" + example + "]";
    }

    /** The region one example's rows for one relation render into. */
    static String rowsRegion(String example, String relation) {
        return example + "-" + relation;
    }

    /**
     * The examples a query file holds, in written order. Every definition is one example, so each
     * must be named, its name being the region a page includes, and must name its fact document.
     */
    static List<Example> examples(Path query) {
        var examples = new java.util.ArrayList<Example>();
        for (graphql.language.Definition<?> definition
                : new Parser().parseDocument(read(query)).getDefinitions()) {
            String name = switch (definition) {
                case graphql.language.OperationDefinition o -> o.getName();
                case graphql.language.FragmentDefinition f -> f.getName();
                default -> throw new IllegalArgumentException(query + " holds a definition that is"
                    + " neither an operation nor a fragment");
            };
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException(query + " holds an unnamed operation; an example's"
                    + " name is the tag region its page includes");
            }
            var paths = ((DirectivesContainer<?>) definition).getDirectives(DIRECTIVE).stream()
                .map(ManualExamples::path).toList();
            if (paths.size() != 1) {
                throw new IllegalArgumentException(query + "'s " + name + " must carry one @"
                    + DIRECTIVE + "(path:), and carries " + paths.size());
            }
            var relations = ((DirectivesContainer<?>) definition).getDirectives(ROWS_DIRECTIVE).stream()
                .map(d -> stringArgument(d, "relation")).toList();
            examples.add(new Example(name, paths.getFirst(),
                graphql.language.AstPrinter.printAst(definition), relations));
        }
        if (examples.stream().map(Example::name).distinct().count() != examples.size()) {
            throw new IllegalArgumentException(query + " names two examples alike");
        }
        return List.copyOf(examples);
    }

    /**
     * The fact document a path names: its folder under {@code facts} and its name, as
     * {@code scenarios/result-external-field}.
     */
    static Optional<Document> factDocument(String path) {
        return FactDocuments.at(path);
    }

    /** What the query file renders to, its header and one region per example: the committed text. */
    static String render(Path query) {
        var prelude = QueryViewRenderer.Prelude.of(FactDocuments.prelude());
        var out = new StringBuilder("# Rendered by ManualExampleTest from " + query.getFileName()
            + ". Do not edit.\n");
        for (Example example : examples(query)) {
            Document document = factDocument(example.path()).orElseThrow(() ->
                new IllegalArgumentException(query + "'s " + example.name() + " names the fact"
                    + " document '" + example.path() + "', which does not exist"));
            out.append("\n# tag::").append(example.name()).append("[]\n")
                .append(wrapDirectives(
                    QueryViewRenderer.render(prelude, Scenario.load(example.path()).composed(),
                        example.selection())))
                .append("\n# end::").append(example.name()).append("[]\n");
        }
        return out.toString();
    }

    /**
     * What the query file's examples render as tables, one region per example and relation, or
     * nothing where no example shows rows: the committed text of {@link #rowsFile}.
     */
    static Optional<String> renderRows(Path query) {
        var csvReader = DSL.using(SQLDialect.H2);
        var out = new StringBuilder("// Rendered by ManualExampleTest from " + query.getFileName()
            + ". Do not edit.\n");
        boolean any = false;
        for (Example example : examples(query)) {
            if (example.relations().isEmpty()) continue;
            Document document = factDocument(example.path()).orElseThrow(() ->
                new IllegalArgumentException(query + "'s " + example.name() + " names the fact"
                    + " document '" + example.path() + "', which does not exist"));
            List<Block> blocks = CorpusExpectations.declaredBlocks(csvReader, document.id(),
                document.sdl());
            for (String relation : example.relations()) {
                var matching = blocks.stream()
                    .filter(b -> b.relation().equalsIgnoreCase(relation))
                    .filter(b -> b.mode() == CorpusExpectations.Mode.EQUALS
                        || b.mode() == CorpusExpectations.Mode.CONTAINS)
                    .toList();
                if (matching.size() != 1) {
                    throw new IllegalArgumentException(query + "'s " + example.name() + " shows '"
                        + relation + "', which " + example.path() + " asserts " + matching.size()
                        + " times; it must assert it once");
                }
                String region = rowsRegion(example.name(), relation);
                out.append("\n// tag::").append(region).append("[]\n")
                    .append(table(matching.getFirst()))
                    .append("// end::").append(region).append("[]\n");
                any = true;
            }
        }
        return any ? Optional.of(out.toString()) : Optional.empty();
    }

    /** One assertion's rows as an AsciiDoc table, each value as code and an empty cell left empty. */
    private static String table(Block block) {
        var out = new StringBuilder();
        out.append("[cols=\"").append(String.join(",", block.columns().stream().map(c -> "1").toList()))
            .append("\",options=\"header\"]\n|===\n");
        for (String column : block.columns()) {
            out.append("| ").append(column).append('\n');
        }
        for (List<String> row : block.rows()) {
            out.append('\n');
            for (String cell : row) {
                out.append(cell == null || cell.isEmpty() ? "|" : "| `" + cell.replace("|", "\\|") + "`")
                    .append('\n');
            }
        }
        return out.append("|===\n").toString();
    }

    /** The width past which a line's directives go one to a line beneath it. */
    private static final int WIDTH = 100;

    /**
     * The rendered SDL with each over-long line laid out as an author writes a busy field: its
     * arguments one to a line inside the parentheses, and its directives one to a line beneath it.
     * The printer puts a field, its arguments and all its directives on one line, which a page
     * cannot show without scrolling.
     */
    static String wrapDirectives(String sdl) {
        var lines = new java.util.ArrayList<String>();
        for (String line : sdl.split("\n", -1)) {
            lines.addAll(wrapArguments(line));
        }
        var out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) {
                out.append('\n');
            }
            List<Integer> breaks = directiveStarts(line);
            if (line.length() <= WIDTH || breaks.isEmpty()) {
                out.append(line);
                continue;
            }
            String indent = line.substring(0, line.length() - line.stripLeading().length()) + "    ";
            int from = 0;
            for (int at : breaks) {
                out.append(line, from, at).append('\n').append(indent);
                from = at + 1;
            }
            out.append(line.substring(from));
        }
        return out.toString();
    }

    /**
     * A field whose name and arguments alone run past the width, split into the field and its
     * opening parenthesis, one line per argument, and the closing parenthesis with what follows it.
     * A short signature stays whole, its directives wrapping beneath it.
     */
    private static List<String> wrapArguments(String line) {
        int open = line.indexOf('(');
        if (line.length() <= WIDTH || open < 0 || line.stripLeading().startsWith("@")
                || line.substring(0, open).contains("@")) {
            return List.of(line);
        }
        int depth = 0;
        boolean quoted = false;
        int close = -1;
        var commas = new java.util.ArrayList<Integer>();
        for (int i = open; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && line.charAt(i - 1) != '\\') {
                quoted = !quoted;
            } else if (!quoted && (c == '(' || c == '[' || c == '{')) {
                depth++;
            } else if (!quoted && (c == ')' || c == ']' || c == '}')) {
                depth--;
                if (depth == 0) {
                    close = i;
                    break;
                }
            } else if (!quoted && depth == 1 && c == ',') {
                commas.add(i);
            }
        }
        if (close < 0 || close + 1 <= WIDTH) {
            return List.of(line);
        }
        String indent = line.substring(0, line.length() - line.stripLeading().length());
        var out = new java.util.ArrayList<String>();
        out.add(line.substring(0, open + 1));
        int from = open + 1;
        for (int at : commas) {
            out.add(indent + "    " + line.substring(from, at + 1).strip());
            from = at + 1;
        }
        out.add(indent + "    " + line.substring(from, close).strip());
        out.add(indent + line.substring(close));
        return out;
    }

    /** Where each directive on the line starts, outside strings and parentheses: the space before its {@code @}. */
    private static List<Integer> directiveStarts(String line) {
        var starts = new java.util.ArrayList<Integer>();
        int depth = 0;
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' && (i == 0 || line.charAt(i - 1) != '\\')) {
                quoted = !quoted;
            } else if (!quoted && (c == '(' || c == '[' || c == '{')) {
                depth++;
            } else if (!quoted && (c == ')' || c == ']' || c == '}')) {
                // A wrapped field's closing line opens on the parenthesis its arguments closed.
                depth = Math.max(0, depth - 1);
            } else if (!quoted && depth == 0 && c == '@' && i > 0 && line.charAt(i - 1) == ' ') {
                starts.add(i - 1);
            }
        }
        return starts;
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String path(Directive directive) {
        return stringArgument(directive, "path");
    }

    private static String stringArgument(Directive directive, String name) {
        return Optional.ofNullable(directive.getArgument(name))
            .map(Argument::getValue)
            .filter(StringValue.class::isInstance)
            .map(v -> ((StringValue) v).getValue())
            .orElseThrow(() -> new IllegalArgumentException(
                "@" + directive.getName() + " needs " + name + ": as a string"));
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.lastIndexOf('.'));
    }

    private static List<Path> files(String suffix) {
        Path root = docs();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> !root.relativize(p).startsWith("target"))
                .filter(p -> p.getParent().getFileName().toString().equals(FOLDER))
                .filter(p -> p.getFileName().toString().endsWith(suffix))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
