package io.github.alexandert02.idempotency.internal.web;

import io.github.alexandert02.idempotency.IdempotencyKeyResolver;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdempotencyKeyResolverSelectorTest {

    @Test
    void explicitQualifierOverridesPrimaryResolver() {
        try (var context = new AnnotationConfigApplicationContext(QualifiedResolvers.class)) {
            var selector = new IdempotencyKeyResolverSelector(context.getBeanFactory());

            assertThat(selector.select("orders"))
                    .isSameAs(context.getBean("orderResolver", IdempotencyKeyResolver.class));
            assertThat(selector.select(""))
                    .isSameAs(context.getBean("defaultResolver", IdempotencyKeyResolver.class));
        }
    }

    @Test
    void explicitBeanNameSelectsResolver() {
        try (var context = new AnnotationConfigApplicationContext(QualifiedResolvers.class)) {
            var selector = new IdempotencyKeyResolverSelector(context.getBeanFactory());

            assertThat(selector.select("orderResolver"))
                    .isSameAs(context.getBean("orderResolver", IdempotencyKeyResolver.class));
        }
    }

    @Test
    void unknownQualifierIsRejected() {
        try (var context = new AnnotationConfigApplicationContext(QualifiedResolvers.class)) {
            var selector = new IdempotencyKeyResolverSelector(context.getBeanFactory());

            assertThatThrownBy(() -> selector.select("missing"))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    @Test
    void ambiguousDefaultResolverIsRejected() {
        try (var context = new AnnotationConfigApplicationContext(AmbiguousResolvers.class)) {
            assertThatThrownBy(() -> new IdempotencyKeyResolverSelector(context.getBeanFactory()))
                    .isInstanceOf(NoUniqueBeanDefinitionException.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class QualifiedResolvers {

        @Bean
        @Primary
        IdempotencyKeyResolver defaultResolver() {
            return (request, handler) -> "default";
        }

        @Bean
        @Qualifier("orders")
        IdempotencyKeyResolver orderResolver() {
            return (request, handler) -> "order";
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class AmbiguousResolvers {

        @Bean
        IdempotencyKeyResolver firstResolver() {
            return (request, handler) -> "first";
        }

        @Bean
        IdempotencyKeyResolver secondResolver() {
            return (request, handler) -> "second";
        }
    }
}
