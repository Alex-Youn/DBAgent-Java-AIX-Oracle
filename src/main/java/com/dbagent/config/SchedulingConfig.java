package com.dbagent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

// 이 빈이 없으면 @Scheduled 작업(AuthService 만료세션 정리, InstanceMetricHistoryService 이력
// 정리, InstanceMetricSamplerService 샘플링)이 Spring Boot 기본값인 스레드 1개짜리 공용
// 스케줄러를 전부 같이 쓴다 - 샘플러가 자기 작업을 병렬화해도(InstanceMetricSamplerService의
// SAMPLER_EXECUTOR) sampleAll() 자체를 실행/대기하는 스레드는 여전히 이 공용 스케줄러의 것 하나뿐이라,
// 그 스레드가 길게 점유되면 나머지 스케줄 작업이 뒤로 밀린다(2026-09-18, 데이터 수집 부하 조사).
@Configuration
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("dbagent-scheduler-");
        return scheduler;
    }
}
