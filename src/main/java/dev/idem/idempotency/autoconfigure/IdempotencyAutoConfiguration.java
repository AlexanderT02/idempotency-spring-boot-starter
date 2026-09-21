package dev.idem.idempotency.autoconfigure;

import dev.idem.idempotency.IdempotencyFingerprintResolver;
import dev.idem.idempotency.internal.metrics.IdempotencyMetrics;
import dev.idem.idempotency.internal.metrics.MicrometerIdempotencyMetrics;
import dev.idem.idempotency.internal.web.DefaultIdempotencyFingerprintResolver;
import dev.idem.idempotency.internal.web.IdempotencyFilter;
import dev.idem.idempotency.internal.web.IdempotencyInterceptor;
import dev.idem.idempotency.internal.web.IdempotencyKeyResolverSelector;
import dev.idem.idempotency.store.IdempotencyStore;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Configures idempotency handling with application-provided resolver and store beans. */
@AutoConfiguration(afterName = "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration")
@EnableConfigurationProperties(IdempotencyProperties.class)
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    IdempotencyFingerprintResolver idempotencyFingerprintResolver() {
        return new DefaultIdempotencyFingerprintResolver();
    }

    @Bean
    IdempotencyInterceptor idempotencyInterceptor(IdempotencyStore store, IdempotencyProperties properties,
                                                  ObjectProvider<IdempotencyMetrics> metrics,
                                                  IdempotencyFingerprintResolver fingerprintResolver) {
        return new IdempotencyInterceptor(store, properties,
                metrics.getIfAvailable(() -> IdempotencyMetrics.NOOP), fingerprintResolver);
    }

    @Bean
    IdempotencyKeyResolverSelector idempotencyKeyResolverSelector(BeanFactory beanFactory) {
        return new IdempotencyKeyResolverSelector(beanFactory);
    }

    @Bean
    IdempotencyFilter idempotencyFilter(
            @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping,
            IdempotencyKeyResolverSelector keyResolvers,
            IdempotencyProperties properties,
            ObjectProvider<IdempotencyMetrics> metrics) {
        return new IdempotencyFilter(handlerMapping, keyResolvers,
                Math.toIntExact(properties.maxRequestBodySize().toBytes()),
                Math.toIntExact(properties.maxResponseBodySize().toBytes()),
                metrics.getIfAvailable(() -> IdempotencyMetrics.NOOP));
    }

    @Bean
    WebMvcConfigurer idempotencyWebMvcConfigurer(IdempotencyInterceptor interceptor) {
        return new WebMvcConfigurer() {
            @Override
            public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor);
            }
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "idempotency.metrics", name = "enabled", havingValue = "true")
    static class MetricsConfiguration {

        @Bean
        IdempotencyMetrics idempotencyMetrics(MeterRegistry registry) {
            return new MicrometerIdempotencyMetrics(registry);
        }
    }
}
