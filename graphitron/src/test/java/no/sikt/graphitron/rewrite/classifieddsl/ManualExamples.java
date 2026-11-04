package no.sikt.graphitron.rewrite.classifieddsl;

import graphql.language.Argument;
import graphql.language.Directive;
import graphql.language.DirectivesContainer;
import graphql.language.StringValue;
import graphql.parser.Parser;
import no.sikt.graphitron.model.test.CorpusDocuments.Document;
import no.sikt.graphitron.model.test.FactDocuments;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A manual page's schema example, rendered from a query over a fact document rather than written by
 * hand, so an example cannot show what the store does not hold.
 *
 * <p>An example is two files in an {@code examples} folder beside the page: a {@code .graphql} file
 * holding one operation or fragment marked {@code @factDocument(path:)}, naming the fact document it
 * selects from as its folder and name ({@code scenarios/result-external-field}), and the
 * {@code .graphqls} file {@link QueryViewRenderer} renders from that selection over the document, its
 * real directives kept and the fact documents' own stripped. The page includes the rendered file's
 * {@value #TAG} region, which leaves the header out.
 *
 * <p>The rendered file is committed for the reason {@link CorpusFragmentRenderer} gives for its
 * fragments: an {@code include::} resolves in every build, including the ones that skip tests and
 * could never have rendered it, and {@link ManualExampleTest} holds the committed file to the render.
 */
final class ManualExamples {

    /** The tag region a page includes, so the header stays out of the page. */
    static final String TAG = "example";

    private static final String QUERY_SUFFIX = ".graphql";
    private static final String RENDERED_SUFFIX = ".graphqls";
    private static final String FOLDER = "examples";
    private static final String DIRECTIVE = "factDocument";

    /** The manual's root, resolved from either the reactor root or a module directory. */
    private static final List<Path> ROOT_CANDIDATES = List.of(Path.of("..", "docs", "manual"),
        Path.of("docs", "manual"));

    private ManualExamples() {}

    static Path manual() {
        return ROOT_CANDIDATES.stream().filter(Files::isDirectory).findFirst()
            .orElseThrow(() -> new IllegalStateException("no docs/manual under " + ROOT_CANDIDATES));
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

    /** The query a rendered file is written from, beside it. */
    static Path queryFile(Path rendered) {
        return rendered.resolveSibling(baseName(rendered) + QUERY_SUFFIX);
    }

    /** The line a page beside the {@code examples} folder includes the rendered file with. */
    static String includeLine(Path rendered) {
        return "include::" + FOLDER + "/" + rendered.getFileName() + "[tag=" + TAG + "]";
    }

    /** The fact document path the query names, or empty when no definition carries the directive. */
    static Optional<String> factDocumentPath(String query) {
        var paths = new Parser().parseDocument(query).getDefinitions().stream()
            .filter(DirectivesContainer.class::isInstance)
            .flatMap(d -> ((DirectivesContainer<?>) d).getDirectives(DIRECTIVE).stream())
            .map(ManualExamples::path)
            .toList();
        if (paths.size() > 1) {
            throw new IllegalArgumentException("an example selects from one fact document, and this"
                + " one names " + paths.size() + ": " + paths);
        }
        return paths.stream().findFirst();
    }

    /**
     * The fact document a path names: its folder under {@code facts} and its name, as
     * {@code scenarios/result-external-field}.
     */
    static Optional<Document> factDocument(String path) {
        return FactDocuments.claimed().entrySet().stream()
            .filter(e -> (e.getValue().folder().name() + "/" + e.getKey().id()).equals("facts/" + path))
            .map(java.util.Map.Entry::getKey)
            .findFirst();
    }

    /** What the query renders to, header and tag region included: the committed file's whole text. */
    static String render(Path query) {
        String text = read(query);
        String path = factDocumentPath(text).orElseThrow(() -> new IllegalArgumentException(
            query + " carries no @" + DIRECTIVE + "(path:), so it names nothing to select from"));
        Document document = factDocument(path).orElseThrow(() -> new IllegalArgumentException(
            query + " names the fact document '" + path + "', which does not exist"));
        String sdl = QueryViewRenderer.render(
            QueryViewRenderer.Prelude.of(FactDocuments.prelude()), document.sdl(), text);
        return "# Rendered by ManualExampleTest from " + query.getFileName() + " over the fact document "
            + path + ". Do not edit.\n"
            + "# tag::" + TAG + "[]\n"
            + sdl + "\n"
            + "# end::" + TAG + "[]\n";
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String path(Directive directive) {
        return Optional.ofNullable(directive.getArgument("path"))
            .map(Argument::getValue)
            .filter(StringValue.class::isInstance)
            .map(v -> ((StringValue) v).getValue())
            .orElseThrow(() -> new IllegalArgumentException("@" + DIRECTIVE + " needs path: as a string"));
    }

    private static String baseName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.lastIndexOf('.'));
    }

    private static List<Path> files(String suffix) {
        try (Stream<Path> walk = Files.walk(manual())) {
            return walk.filter(p -> p.getParent().getFileName().toString().equals(FOLDER))
                .filter(p -> p.getFileName().toString().endsWith(suffix))
                .sorted()
                .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
