package no.sikt.graphitron.rewrite.test.tier;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a test class as compilation tier: generated source must compile against the real jOOQ catalog. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("compilation")
public @interface CompilationTier {}
