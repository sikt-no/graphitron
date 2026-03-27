package no.sikt.graphitron.mappings;

import no.sikt.graphitron.configuration.GeneratorConfig;
import org.jetbrains.annotations.NotNull;
import org.jooq.Catalog;
import org.jooq.Schema;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Base class for jOOQ reflection operations. Provides shared infrastructure for discovering
 * and reflecting on jOOQ-generated schema classes (Tables, Keys, Routines, etc.).
 */
public class SchemaReflection {

    protected static Catalog getDefaultCatalog() {
        var generatedJooqPackage = GeneratorConfig.getGeneratedJooqPackage();

        try {
            var defaultCatalogClass = Class.forName(generatedJooqPackage + ".DefaultCatalog");
            var defaultCatalogField = defaultCatalogClass.getField("DEFAULT_CATALOG");
            return (Catalog) defaultCatalogField.get(null);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(generatedJooqPackage + " did not contain a DefaultCatalog class. This is probably a configuration error.", e);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(generatedJooqPackage + ".DefaultCatalog did not contain the DEFAULT_CATALOG field. Inconceivable.", e);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Could not get " + generatedJooqPackage + ".DefaultCatalog.DEFAULT_CATALOG. Inconceivable.", e);
        }
    }

    protected static Function<Schema, Stream<java.lang.reflect.Field>> getFieldsFromSchemaClass(String className) {
        return schema -> findClassFromSchemaPackage(schema, className).stream().flatMap(it -> Arrays.stream(it.getFields()));
    }

    @NotNull
    protected static Function<Schema, Class<?>> getClassFromSchemaPackage(String className) {
        return schema -> findClassFromSchemaPackage(schema, className)
                .orElseThrow(() -> new RuntimeException(schema.getClass().getPackageName() + " did not contain a " + className + " class. Inconceivable."));
    }

    /**
     * Looks up a class that jOOQ generates alongside a schema (e.g. {@code Tables}, {@code Keys}, {@code Routines}).
     * Returns empty when the class does not exist — jOOQ omits these classes for schemas with no tables/keys/routines,
     * and callers that aggregate across all schemas need to tolerate that.
     */
    private static Optional<Class<?>> findClassFromSchemaPackage(Schema schema, String className) {
        var packageName = schema.getClass().getPackageName();
        try {
            return Optional.of(Class.forName(packageName + "." + className));
        } catch (ClassNotFoundException e) {
            return Optional.empty();
        }
    }

    protected static String getJavaFieldName(java.lang.reflect.Field field) {
        return field.getName();
    }

    protected static Object getJavaFieldValue(java.lang.reflect.Field field) {
        try {
            return field.get(null);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
