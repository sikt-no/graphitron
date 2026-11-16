package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.classpath.ClassfileCensus;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.module.ModuleDescriptor;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The platform's throwables, read off the runtime image as the census reads a classpath entry: the
 * public classes of {@code java.base}'s and {@code java.sql}'s exported packages whose superclass
 * chain reaches {@code java.lang.Throwable}, each parsed whole.
 *
 * <p>The JDK is a corpus of its own, a source of kind {@code PLATFORM}: the exceptions a method
 * declares and a handler names are mostly its, and what they offer and extend is read from it as
 * from any entry. The population is fixed rather than what one graph happens to name, because the
 * source is shared by every graph and a reading scoped to one graph's demand would sweep the rows
 * another needs.
 *
 * <p>The image is the generator's runtime, not the consumer's {@code --release}, the gap
 * {@link ClassAncestry} already accepts for supertypes.
 *
 * <p>The parse is memoized per JVM, keyed by the image's stamp. That is a cache of a parse, not of
 * store facts: every store still writes, owns and sweeps its own rows of this source on
 * {@code store_source}'s terms, and only the reading of the image is shared, which a test run
 * opening hundreds of fresh stores would otherwise repeat for each.
 */
final class PlatformClasses {

    /** The source the platform's rows are recorded under. */
    static final String SOURCE = "jrt:/";

    /** The modules read: the throwables of the language and of JDBC. */
    private static final List<String> MODULES = List.of("java.base", "java.sql");

    private static final String THROWABLE = "java.lang.Throwable";

    private static final Map<String, List<ClassfileCensus.ClassAt>> READ = new ConcurrentHashMap<>();

    private PlatformClasses() {}

    /** What the image is: the runtime's version and vendor, which is what the classes vary with. */
    static String stamp() {
        return Runtime.version() + " " + System.getProperty("java.vendor");
    }

    /** The platform's throwables, parsed once per JVM for the image it runs on. */
    static List<ClassfileCensus.ClassAt> throwables() {
        return READ.computeIfAbsent(stamp(), stamp -> read());
    }

    private static List<ClassfileCensus.ClassAt> read() {
        var jrt = FileSystems.getFileSystem(URI.create("jrt:/"));
        Path modules = jrt.getPath("/modules");
        Map<String, ClassfileCensus.Header> headers = new HashMap<>();
        Map<String, Path> files = new HashMap<>();
        try {
            for (String module : MODULES) {
                Set<String> exported = ModuleLayer.boot().findModule(module).orElseThrow()
                    .getDescriptor().exports().stream()
                    .filter(export -> !export.isQualified())
                    .map(ModuleDescriptor.Exports::source)
                    .collect(Collectors.toSet());
                Path root = modules.resolve(module);
                try (var walk = Files.walk(root)) {
                    for (Path file : walk.filter(p -> p.toString().endsWith(".class")).toList()) {
                        String relative = root.relativize(file).toString();
                        int slash = relative.lastIndexOf('/');
                        if (slash < 0 || !exported.contains(relative.substring(0, slash).replace('/', '.'))) {
                            continue;
                        }
                        ClassfileCensus.header(Files.readAllBytes(file)).ifPresent(header -> {
                            headers.put(header.className(), header);
                            files.put(header.className(), file);
                        });
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not read the runtime image", e);
        }
        var out = new ArrayList<ClassfileCensus.ClassAt>();
        for (var name : headers.keySet().stream().sorted().toList()) {
            if (reachesThrowable(name, headers)) {
                ClassfileCensus.readFile(modules, files.get(name), SOURCE, null).ifPresent(out::add);
            }
        }
        return List.copyOf(out);
    }

    /** Whether the superclass chain reaches Throwable within the image's exported classes. */
    private static boolean reachesThrowable(String name, Map<String, ClassfileCensus.Header> headers) {
        String at = name;
        var walked = new java.util.HashSet<String>();
        while (at != null && walked.add(at)) {
            if (THROWABLE.equals(at)) {
                return true;
            }
            at = Optional.ofNullable(headers.get(at)).flatMap(header -> header.supertypes().stream()
                    .filter(supertype -> "EXTENDS".equals(supertype.declaredVia()))
                    .map(ClassfileCensus.SupertypeAt::name)
                    .findFirst())
                .orElse(null);
        }
        return false;
    }
}
