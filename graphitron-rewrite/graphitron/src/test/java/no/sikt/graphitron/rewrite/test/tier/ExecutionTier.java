package no.sikt.graphitron.rewrite.test.tier;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Marks a test class as execution tier: full GraphQL request → SQL → row round-trip against PostgreSQL. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Tag("execution")
public @interface ExecutionTier {}
