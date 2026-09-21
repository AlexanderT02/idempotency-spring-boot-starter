package io.github.alexandert02.idempotency;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Enables idempotency handling for a Spring MVC endpoint. */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface Idempotent {

    /** Claim TTL override in seconds; {@code 0} uses {@code idempotency.ttl}. */
    long ttlSeconds() default 0;

    /** Key resolver bean name or qualifier; empty uses the default resolver. */
    String keyResolver() default "";
}
