package no.sikt.graphitron.rewrite.model;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Container for repeated {@link LoadBearingClassifierCheck} declarations on a
 * single producer site. Generated implicitly via {@code @Repeatable}; never
 * written by hand.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface LoadBearingClassifierChecks {
    LoadBearingClassifierCheck[] value();
}
