package no.sikt.graphitron.rewrite.fixtures.codegen;

import org.jooq.meta.SchemaDefinition;
import org.jooq.meta.SchemaVersionProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A fixture catalog's schema version: the SHA-256 of the files it is generated from, so jOOQ
 * regenerates exactly when one of them changes and nobody keeps a version by hand. The files are the
 * comma-separated paths of the {@code catalogInputs} property in the codegen execution's
 * {@code <database><properties>}, read in the order given; graphitron-sakila-db names its
 * {@code init.sql} and its own pom, whose codegen configuration is an input too.
 */
public class CatalogInputsSchemaVersionProvider implements SchemaVersionProvider {

    /** The {@code <database><properties>} key naming the inputs. */
    public static final String INPUTS_PROPERTY = "catalogInputs";

    @Override
    public String version(SchemaDefinition schema) {
        String inputs = schema.getDatabase().getProperties().getProperty(INPUTS_PROPERTY);
        if (inputs == null || inputs.isBlank()) {
            throw new IllegalStateException("the codegen execution names no " + INPUTS_PROPERTY
                + " in its <database><properties>, so the catalog's version has nothing to hash");
        }
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        for (String input : inputs.split(",")) {
            Path path = Path.of(input.strip());
            try {
                digest.update(Files.readAllBytes(path));
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read catalog input " + path, e);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
