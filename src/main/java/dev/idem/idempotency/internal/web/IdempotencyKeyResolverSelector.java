package dev.idem.idempotency.internal.web;

import dev.idem.idempotency.IdempotencyKeyResolver;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.BeanFactoryAnnotationUtils;
import org.springframework.util.StringUtils;

/** Selects the default or explicitly qualified key resolver. */
public final class IdempotencyKeyResolverSelector {

    private final BeanFactory beanFactory;
    private final IdempotencyKeyResolver defaultResolver;

    public IdempotencyKeyResolverSelector(BeanFactory beanFactory) {
        this.beanFactory = beanFactory;
        this.defaultResolver = beanFactory.getBeanProvider(IdempotencyKeyResolver.class).getObject();
    }

    IdempotencyKeyResolver select(String qualifier) {
        if (StringUtils.hasText(qualifier)) {
            return BeanFactoryAnnotationUtils.qualifiedBeanOfType(
                    beanFactory, IdempotencyKeyResolver.class, qualifier);
        }
        return defaultResolver;
    }
}
