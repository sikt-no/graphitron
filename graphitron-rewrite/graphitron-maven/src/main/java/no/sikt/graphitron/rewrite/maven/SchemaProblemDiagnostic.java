package no.sikt.graphitron.rewrite.maven;

import graphql.GraphQLError;
import graphql.schema.idl.errors.SchemaProblem;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Builds a human-readable error message from a graphql-java {@link SchemaProblem}.
 * The default {@code SchemaProblem.getMessage()} renders as
 * {@code errors=[A schema MUST have a 'query' operation defined]}, which buries
 * the cause and gives the user no clue about which schema files were actually
 * loaded. This helper expands that into a multi-line diagnostic listing each
 * underlying error, the schema files Graphitron handed to graphql-java, and any
 * orphan {@code *.graphql}/{@code *.graphqls} files found under {@code src/main}
 * that the {@code <schemaInputs>} configuration did not pick up.
 */
final class SchemaProblemDiagnostic {

    private static final Path SRC_MAIN = Path.of("src", "main");
    private static final Path TARGET = Path.of("target");

    private SchemaProblemDiagnostic() {}

    static String format(SchemaProblem problem, List<String> loadedSourceNames, Path basedir) {
        var loadedAbs = normaliseLoaded(loadedSourceNames, basedir);
        var orphans = findOrphanSchemaFiles(loadedAbs, basedir);

        var sb = new StringBuilder();
        sb.append("GraphQL schema validation failed:");
        for (GraphQLError e : problem.getErrors()) {
            sb.append("\n  - ").append(e.getMessage());
        }

        sb.append("\n\nSchema files loaded by Graphitron (").append(loadedAbs.size()).append(")");
        if (loadedAbs.isEmpty()) {
            sb.append(":\n  (none — <schemaInputs> is empty or no patterns matched)");
        } else {
            sb.append(':');
            for (Path p : sortRelative(loadedAbs, basedir)) {
                sb.append("\n  ").append(p);
            }
        }

        if (!orphans.isEmpty()) {
            sb.append("\n\nFound under ").append(SRC_MAIN)
              .append(" but not declared in <schemaInputs> (").append(orphans.size()).append("):");
            for (Path p : orphans) {
                sb.append("\n  ").append(p);
            }
        }

        sb.append("\n\nHint: declare a 'type Query { ... }' in one of the loaded files,");
        sb.append("\nor add the missing file to <schemaInputs> in graphitron-maven.");
        return sb.toString();
    }

    private static Set<Path> normaliseLoaded(List<String> sourceNames, Path basedir) {
        var out = new LinkedHashSet<Path>();
        for (String s : sourceNames) {
            Path p = Path.of(s);
            out.add(p.isAbsolute() ? p.normalize() : basedir.resolve(p).toAbsolutePath().normalize());
        }
        return out;
    }

    private static List<Path> sortRelative(Set<Path> abs, Path basedir) {
        var sorted = new TreeSet<String>();
        for (Path p : abs) {
            sorted.add(relativise(p, basedir));
        }
        return sorted.stream().map(Path::of).toList();
    }

    private static String relativise(Path abs, Path basedir) {
        try {
            return basedir.toAbsolutePath().normalize().relativize(abs).toString();
        } catch (IllegalArgumentException e) {
            return abs.toString();
        }
    }

    static List<Path> findOrphanSchemaFiles(Set<Path> loadedAbs, Path basedir) {
        Path scanRoot = basedir.resolve(SRC_MAIN);
        if (!Files.isDirectory(scanRoot)) return List.of();

        Path targetAbs = basedir.resolve(TARGET).toAbsolutePath().normalize();
        var orphans = new TreeSet<String>();
        try (Stream<Path> walk = Files.walk(scanRoot)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> {
                    String n = p.getFileName().toString();
                    return n.endsWith(".graphql") || n.endsWith(".graphqls");
                })
                .map(p -> p.toAbsolutePath().normalize())
                .filter(p -> !p.startsWith(targetAbs))
                .filter(p -> !loadedAbs.contains(p))
                .forEach(p -> orphans.add(relativise(p, basedir)));
        } catch (IOException e) {
            return List.of();
        }
        return orphans.stream().map(Path::of).toList();
    }
}
