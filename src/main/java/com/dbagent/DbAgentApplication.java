package com.dbagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// AuthService의 만료 세션 정리 작업(@Scheduled)을 돌리기 위해 필요 (사용자 요청, 2026-08-29: 세션
// 테이블이 로그아웃 없이는 무제한으로 쌓이는 문제 - 30일 TTL 정리 작업 추가).
@EnableScheduling
@SpringBootApplication
public class DbAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(DbAgentApplication.class, args);
    }
}
