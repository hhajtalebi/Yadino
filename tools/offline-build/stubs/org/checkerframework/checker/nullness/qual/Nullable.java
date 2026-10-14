package org.checkerframework.checker.nullness.qual;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compile-only stub of checker-qual's @Nullable (used by auto-common sources). */
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.TYPE_USE, ElementType.TYPE_PARAMETER})
public @interface Nullable {
}
