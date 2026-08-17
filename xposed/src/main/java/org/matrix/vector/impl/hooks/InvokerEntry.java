package org.matrix.vector.impl.hooks;

import io.github.libxposed.api.XposedInterface.CtorInvoker;
import io.github.libxposed.api.XposedInterface.Invoker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;

/**
 * The vararg doors of {@link Invoker}, in Java because Kotlin bars a null array from one.
 *
 * <p>{@code Object invoke(Object, Object...)} lets a Java module write
 * {@code invoke(obj, (Object[]) null)}, and reflection reads that null array as no arguments at
 * all: {@link Method#invoke} returns normally for a zero-parameter executable and reports
 * IllegalArgumentException for any other. A Kotlin {@code vararg args: Any?} override compiles to
 * a non-null {@code Object[]} parameter with an {@code Intrinsics.checkNotNullParameter(args,
 * "args")} ahead of its first statement, so the same call is a NullPointerException before any of
 * that can be decided. Java emits no such check.
 *
 * <p>That is the only reason these four methods are not in BaseInvoker.kt: every line of decision
 * is still Kotlin's, behind the {@code ...With} methods. Turning the check off with
 * {@code -Xno-param-assertions} is not the alternative - it is a module-wide flag, and
 * VectorChain's own {@code proceed(Object[])} is declared @NonNull, where the NullPointerException
 * is correct.
 */
public interface InvokerEntry<T extends Invoker<T, U>, U extends Executable> extends Invoker<T, U> {

    Object[] NO_ARGS = new Object[0];

    @Override
    default Object invoke(Object thisObject, Object... args) {
        return invokeWith(thisObject, args == null ? NO_ARGS : args);
    }

    @Override
    default Object invokeSpecial(Object thisObject, Object... args) {
        return invokeSpecialWith(thisObject, args == null ? NO_ARGS : args);
    }

    Object invokeWith(Object thisObject, Object[] args);

    Object invokeSpecialWith(Object thisObject, Object[] args);

    /** The same doors for the two entry points {@link CtorInvoker} adds. */
    interface Ctor<T> extends InvokerEntry<CtorInvoker<T>, Constructor<T>>, CtorInvoker<T> {

        @Override
        default T newInstance(Object... args) throws InstantiationException {
            return newInstanceWith(args == null ? NO_ARGS : args);
        }

        @Override
        default <V> V newInstanceSpecial(Class<V> subClass, Object... args)
                throws InstantiationException {
            return newInstanceSpecialWith(subClass, args == null ? NO_ARGS : args);
        }

        T newInstanceWith(Object[] args) throws InstantiationException;

        <V> V newInstanceSpecialWith(Class<V> subClass, Object[] args)
                throws InstantiationException;
    }
}
