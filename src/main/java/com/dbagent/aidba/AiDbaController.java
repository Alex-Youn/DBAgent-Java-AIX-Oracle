package com.dbagent.aidba;

import com.dbagent.util.Maps;
import com.dbagent.util.Strings;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/aidba")
public class AiDbaController {

    private final ErrorSearchService errorSearchService;

    public AiDbaController(ErrorSearchService errorSearchService) {
        this.errorSearchService = errorSearchService;
    }

    @GetMapping("/error_search")
    public ResponseEntity<Map<String, Object>> errorSearch(
            @RequestParam(name = "code", required = false, defaultValue = "") String code) {
        if (Strings.isBlank(code)) {
            return ResponseEntity.badRequest().body(Maps.of("error", "No error code provided"));
        }
        return ResponseEntity.ok(errorSearchService.getErrorSolution(code));
    }
}
