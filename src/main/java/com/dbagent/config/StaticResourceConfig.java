package com.dbagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// 사용자가 index.html/app.js/fleet-overview.html을 고쳐 재배포해도 브라우저가 예전 응답을 그대로
// 재사용해 "고쳤는데 안 바뀐다"는 증상이 반복됐다 (원본 DBAgent-Java에서 2026-08-29 app.js?v= 번호
// 재사용 충돌, 2026-08-30 fleet-overview.html 자체도 동일 증상) - Spring Boot 기본 정적 리소스
// 핸들러는 Cache-Control 헤더를 안 보내서, 브라우저가 Last-Modified만 보고 자체 판단(heuristic
// caching)으로 네트워크 요청 자체를 생략해버릴 수 있는 게 원인. no-cache로 강제하면 매번 서버에
// 조건부 GET(If-Modified-Since)으로 재검증하게 되어, 파일이 실제로 바뀌었으면 반드시 새 응답을
// 받는다 - 안 바뀐 경우엔 여전히 304로 응답해 매번 새로 다운로드하는 비용은 없다.
@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCacheControl(CacheControl.noCache());
    }
}
