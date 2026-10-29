package no.sikt.graphitron.rewrite.test.services;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Fixture: a class-level constraint on {@link FilmBundleDraft}, holding when the bundle's films fit
 * its capacity. A violation of it belongs to the whole argument rather than to any one field.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = FilmBundleFits.Check.class)
public @interface FilmBundleFits {

    String message() default "the films do not fit the bundle";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** Holds where either side is absent; {@code @Positive} and the SDL own those cases. */
    final class Check implements ConstraintValidator<FilmBundleFits, FilmBundleDraft> {
        @Override
        public boolean isValid(FilmBundleDraft bundle, ConstraintValidatorContext context) {
            return bundle == null || bundle.capacity() == null || bundle.films() == null
                || bundle.films().size() <= bundle.capacity();
        }
    }
}
