package com.dbagent.store;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;

/** {@link ConditionalOnStoreType}의 판정. */
public class StoreTypeCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(ConditionalOnStoreType.class.getName());
        if (attrs == null) return true;
        StoreId id = (StoreId) attrs.get("id");
        StoreType wanted = (StoreType) attrs.get("type");
        return StoreTypes.resolve(context.getEnvironment(), id) == wanted;
    }
}
