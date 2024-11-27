package no.sikt.graphitron.mojo;

import no.sikt.graphitron.configuration.GeneratorConfig;
import no.sikt.graphitron.generators.abstractions.ClassGenerator;
import no.sikt.graphql.schema.ProcessedSchema;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.file.StandardWatchEventKinds.*;
import static no.sikt.graphitron.mojo.GraphQLGenerator.getProcessedSchema;

/**
 * Mojo for watching the schema files for changes, and regenerating upon updates.
 */
@Mojo(name = "watch")
public class WatcherMojo extends GenerateMojo {
    /**
     * Wait for this many millis have passed since the last change before regenerating files.
     */
    private final static long WAIT_DELAY_MILLIS = 2500;

    /**
     * Protect from unlikely edge cases where too many things happen at once
     * by limiting the amount of keys that can be gathered at any one time.
     */
    private final static int MAX_KEY_LIMIT = 10000;
    /**
     * Regex for identifying graphql files.
     */
    private final static Pattern GRAPHQL_FILE_PATTERN = Pattern.compile("\\.graphqls?$");

    private final Function<ProcessedSchema, List<ClassGenerator<?>>> generatorFunction;

    public WatcherMojo() {
        generatorFunction = GraphQLGenerator::getGenerators;
    }

    public WatcherMojo(Function<ProcessedSchema, List<ClassGenerator<?>>> generatorFunction) {
        this.generatorFunction = generatorFunction;
    }

    /**
     * @param delay Custom amount of milliseconds to wait for changes.
     */
    public void executeWithDelay(long delay) {
        GeneratorConfig.loadProperties(this);

        generate(); // In case the code is not already generated when initiating watch.

        // Group the files and find their immediate directories.
        var fileWatchMap = GeneratorConfig
                .schemaFiles()
                .stream()
                .collect(Collectors.groupingBy(it -> new File(it).getParentFile().toPath(), Collectors.toCollection(HashSet::new)));
        getLog().info("Initiating watching of directories:");
        fileWatchMap.keySet().forEach(it -> getLog().info(it.toString()));
        try (var watchService = FileSystems.getDefault().newWatchService()) {
            var watchMap = getWatchMapFromFiles(fileWatchMap, watchService);
            while (true) {
                var keyListOptional = waitForWatchKeys(watchService, delay);
                if (keyListOptional.isEmpty()) {
                    return;
                }

                var keysInWatch = keyListOptional
                        .get()
                        .stream()
                        .filter(watchMap::containsKey)
                        .collect(Collectors.toSet());
                getLog().debug("Keys changed: " + keysInWatch.stream().map(Object::toString).collect(Collectors.joining(", ")));

                var update = false;
                for (var key : keysInWatch) {
                    update = update || shouldUpdateAfterEvents(key, watchMap.get(key), fileWatchMap);
                }

                if (update) { // If an actual change occurs, regenerate files.
                    regenerateFiles(fileWatchMap);
                }
                keysInWatch.forEach(WatchKey::reset);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void execute() throws MojoExecutionException {
        executeWithDelay(WAIT_DELAY_MILLIS);
    }

    /**
     * Wait for and collect a sequence of keys.
     */
    @NotNull
    private Optional<ArrayList<WatchKey>> waitForWatchKeys(WatchService watchService, long delay) {
        var keyList = new ArrayList<WatchKey>();
        WatchKey watchKey;
        do {
            try {
                watchKey = watchService.poll(delay, TimeUnit.MILLISECONDS);
                if (watchKey != null) {
                    keyList.add(watchKey);
                }
            } catch (InterruptedException e) {
                return Optional.empty();
            }
        } while(watchKey != null && keyList.size() < MAX_KEY_LIMIT); // Wait in case many changes appear simultaneously.
        return Optional.of(keyList);
    }

    /**
     * Add each unique directory to the watch.
     */
    @NotNull
    private HashMap<WatchKey, Path> getWatchMapFromFiles(Map<Path, HashSet<String>> fileWatchMap, WatchService watchService) throws IOException {
        var watch = new HashMap<WatchKey, Path>();
        for (var directory : fileWatchMap.keySet()) {
            var watchKey = directory.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
            watch.put(watchKey, directory);
        }
        return watch;
    }

    /**
     * Evaluate whether a relevant update has taken place, and add or remove filenames that are no longer valid.
     */
    private boolean shouldUpdateAfterEvents(WatchKey key, Path directoryPath, Map<Path, HashSet<String>> fileWatchMap) {
        var events = key
                .pollEvents()
                .stream()
                .filter(it -> it.context() instanceof Path)
                .filter(it -> !it.toString().endsWith("~")) // These seem to be temporary files.
                .collect(Collectors.toList());

        var runUpdate = false;
        for (var event : events) { // When an event happens, figure out whether this file + directory should be added or removed.
            getLog().debug("Processing event: " + event.toString());
            var modifiedPath = directoryPath.resolve((Path) event.context()).toString(); // Path to the actual file.
            var fileSet = fileWatchMap.get(directoryPath);
            getLog().debug("Modified path: " + modifiedPath);

            if (fileSet != null && GRAPHQL_FILE_PATTERN.matcher(modifiedPath).find()) { // Only care about graph files.
                var kind = event.kind();
                getLog().debug("Event kind: " + kind);
                if (kind == ENTRY_CREATE) {
                    GeneratorConfig.setSchemaFiles();
                    runUpdate = runUpdate || fileSet.add(modifiedPath);
                } else if (kind == ENTRY_DELETE) {
                    runUpdate = runUpdate || fileSet.remove(modifiedPath);
                } else if (kind == ENTRY_MODIFY) {
                    runUpdate = runUpdate || fileSet.contains(modifiedPath);
                }
            }
        }
        return runUpdate;
    }

    /**
     * Re-run codegen on new schema.
     */
    private void regenerateFiles(Map<Path, HashSet<String>> fileWatchMap) {
        getLog().info("Schema changes detected, generating code...");
        GeneratorConfig.setSchemaFiles(fileWatchMap.values().stream().flatMap(Collection::stream).collect(Collectors.toSet()));
        generate();
    }

    private void generate() {
        delete(new File(GeneratorConfig.outputDirectory())); // This is done to ensure that any obsolete files get removed as well.

        try { // Note that if this fails, code gets deleted anyway.
            var processedSchema = getProcessedSchema();
            processedSchema.validate();
            var generators = generatorFunction.apply(processedSchema);
            for (var g : generators) {
                g.generateQualifyingObjectsToDirectory(GeneratorConfig.outputDirectory(), GeneratorConfig.outputPackage());
            }
        } catch (Exception e) {
            getLog().error("Code generation has failed, an exception was thrown.", e);
            return;
        }
        var thisThread = Thread.currentThread();
        synchronized (thisThread) {
            thisThread.notifyAll(); // If anything is waiting for this to finish. Intended for tests.
        }
        getLog().info("Code has been generated successfully.");
    }

    /**
     * Recursive method for deleting directories.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private void delete(File file) {
        var files = file.listFiles();
        if (files != null) {
            Stream.of(files).forEach(this::delete);
        }

        file.delete();
    }
}
