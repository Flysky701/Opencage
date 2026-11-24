package io.github.flysky701.opencage.privacy.internal;

import java.lang.reflect.Method;

/** Calls the original invocation on a wrapped service proxy. */
public interface OriginalCall {
    Object cageOriginalInvoke(Object proxy, Method method, Object[] args) throws Throwable;
}
