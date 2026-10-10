package no.sikt.graphitron.model.capture.code;

import no.sikt.graphitron.model.classpath.ClassfileCensus;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * The header of a class the census does not hold, read from the classfile rather than by loading
 * the class: from the platform the capture runs on where the platform holds the package, and
 * otherwise from the classpath's entries in order.
 *
 * <p>Only what an ancestry question names is read, and each class once. Entries the census skipped
 * are read here too, the generated jOOQ package and the transitive jars among them, since a
 * consumer class's supertypes may live there.
 */
final class ClassHeaders implements AutoCloseable {

    private final List<Path> entries;
    private final Map<Path, ZipFile> jars = new HashMap<>();
    private final Map<String, Optional<ClassfileCensus.Header>> read = new HashMap<>();
    private FileSystem platform;

    ClassHeaders(List<Path> entries) {
        this.entries = List.copyOf(entries);
    }

    Optional<ClassfileCensus.Header> of(String className) {
        return read.computeIfAbsent(className, this::find);
    }

    private Optional<ClassfileCensus.Header> find(String className) {
        String file = className.replace('.', '/') + ".class";
        // A package the platform holds is the platform's: a classpath cannot declare into it.
        byte[] fromPlatform = platformBytes(className, file);
        if (fromPlatform != null) {
            return ClassfileCensus.header(fromPlatform);
        }
        for (Path entry : entries) {
            byte[] bytes = bytesIn(entry, file);
            if (bytes != null) {
                return ClassfileCensus.header(bytes);
            }
        }
        return Optional.empty();
    }

    private byte[] bytesIn(Path entry, String file) {
        try {
            if (Files.isDirectory(entry)) {
                Path path = entry.resolve(file);
                return Files.isRegularFile(path) ? Files.readAllBytes(path) : null;
            }
            if (!Files.isRegularFile(entry)) {
                return null;
            }
            ZipFile jar = jars.get(entry);
            if (jar == null) {
                jar = new ZipFile(entry.toFile());
                jars.put(entry, jar);
            }
            var zipEntry = jar.getEntry(file);
            if (zipEntry == null) {
                return null;
            }
            try (var in = jar.getInputStream(zipEntry)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            return null;
        }
    }

    /** The class as the platform's modules hold it, found through the package it is in. */
    private byte[] platformBytes(String className, String file) {
        int dot = className.lastIndexOf('.');
        if (dot < 0) {
            return null;
        }
        try {
            Path packageDir = platform().getPath("/packages", className.substring(0, dot));
            if (!Files.isDirectory(packageDir)) {
                return null;
            }
            try (Stream<Path> modules = Files.list(packageDir)) {
                for (Path module : modules.toList()) {
                    Path path = module.resolve(file);
                    if (Files.isRegularFile(path)) {
                        return Files.readAllBytes(path);
                    }
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private FileSystem platform() {
        if (platform == null) {
            try {
                platform = FileSystems.getFileSystem(URI.create("jrt:/"));
            } catch (FileSystemNotFoundException e) {
                try {
                    platform = FileSystems.newFileSystem(URI.create("jrt:/"), Map.of());
                } catch (IOException io) {
                    throw new java.io.UncheckedIOException(io);
                }
            }
        }
        return platform;
    }

    @Override
    public void close() {
        for (ZipFile jar : jars.values()) {
            try {
                jar.close();
            } catch (IOException ignored) {
                // A jar that will not close was only read.
            }
        }
        jars.clear();
    }
}
