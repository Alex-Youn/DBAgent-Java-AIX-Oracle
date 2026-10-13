package com.dbagent.store;

import org.springframework.context.annotation.Conditional;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 해당 저장소의 엔진이 type일 때만 빈을 만든다. 같은 이름의 DataSource 빈을 H2용·Oracle용으로 따로 두고 하나만 살리려고 쓴다
 * (H2 쪽 빈 정의를 한 글자도 바꾸지 않으려는 것 - 1단계 완료 기준 "Oracle을 쓰지 않으면 동작 불변").
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Conditional(StoreTypeCondition.class)
public @interface ConditionalOnStoreType {

    StoreId id();

    StoreType type();
}
