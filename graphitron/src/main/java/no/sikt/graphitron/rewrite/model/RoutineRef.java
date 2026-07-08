package no.sikt.graphitron.rewrite.model;

import no.sikt.graphitron.javapoet.ClassName;
import no.sikt.graphitron.javapoet.TypeName;

import java.util.List;

/**
 * Provenance carrier for a field backed by a jOOQ database <em>routine</em> ({@code @routine}).
 *
 * <p>The database twin of {@link MethodRef} (which carries a developer-authored Java method): where
 * {@code @tableMethod} rides a {@code MethodRef.StaticOnly}, {@code @routine} rides this. A read
 * routine is a catalog handle rather than a reflected developer method, so folding it under
 * {@link MethodRef} would be the "god accessor whose meaning depends on the variant" smell; it gets
 * its own sibling carrier (see R300).
 *
 * <p>Day-one models the table-valued read function only. jOOQ generates such a function as a
 * first-class catalog {@code Table<R>} (so the result projection rides the existing
 * {@link ReturnTypeRef.TableBoundReturnType} machinery), plus a convenience method on the schema's
 * global {@code Routines} class that returns the configured table for use in {@code FROM}. This
 * carrier holds exactly what the emitter needs to invoke that convenience method:
 *
 * <ul>
 *   <li>{@code routinesClass} — the generated {@code Routines} class (e.g. {@code ....jooq.Routines}).</li>
 *   <li>{@code methodName} — the table-form convenience method (e.g. {@code tilgangerForFeidebrukerMedFsFiktivtFnr}).</li>
 *   <li>{@code argBindings} — the routine IN parameters in declaration order, each bound to the
 *       GraphQL field argument supplying its value. Argument binding lives here, at the target
 *       endpoint, because routine inputs parameterise the projected table expression (the same role
 *       {@code @tableMethod}'s args play), not an operation payload.</li>
 * </ul>
 */
public record RoutineRef(ClassName routinesClass, String methodName, List<ArgBinding> argBindings) {
    public RoutineRef {
        argBindings = List.copyOf(argBindings);
    }

    /**
     * One routine IN parameter and the {@link ParamSource} supplying its value. {@code paramType}
     * is the boxed Java type of the routine parameter (e.g. {@code java.lang.String}), used to
     * emit a typed read at the call site.
     *
     * <p>{@code source} is the shared call-source taxonomy ({@link ParamSource}), so a routine
     * parameter has exactly one source shape: {@link ParamSource.Arg} when a GraphQL field
     * argument supplies the value ({@code argMapping}). The other {@link ParamSource} arms are
     * never minted for routine bindings; the resolver produces only the arms named here.
     */
    public record ArgBinding(String routineParamName, TypeName paramType, ParamSource source) {}
}
