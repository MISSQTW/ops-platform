package com.lqbz.ops.inspection;

import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class InspectionProfileController {

    private final InspectionProfileService profiles;

    private final ProbeRunner runner;

    public InspectionProfileController(InspectionProfileService profiles, ProbeRunner runner) {
        this.profiles = profiles;
        this.runner = runner;
    }

    @GetMapping("/api/assets/{id}/inspection-profile")
    public InspectionProfileService.View get(@PathVariable long id) {
        return profiles.view(id);
    }

    @PutMapping("/api/assets/{id}/inspection-profile")
    public InspectionProfileService.View save(@PathVariable long id, @RequestBody InspectionProfileService.Update request) {
        return profiles.save(id, request);
    }

    @PostMapping("/api/assets/{id}/inspection-profile/test")
    public List<ProbeCheck> test(@PathVariable long id) {
        return runner.run(id, false, false);
    }

    @GetMapping("/internal/inspection/{id}")
    public ResponseEntity<String> probe(@PathVariable long id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        long revision = profiles.authorizedRevision(id, authorization);
        if (revision < 0)
            return ResponseEntity.status(401).body("UNAUTHORIZED");
        try {
            List<ProbeCheck> checks = runner.run(id, true, true, revision);
            boolean passed = !checks.isEmpty() && checks.stream().allMatch(ProbeCheck::passed);
            return ResponseEntity.status(passed ? 200 : 503).body(passed ? "PASSED" : "FAILED");
        } catch (RuntimeException exception) {
            // 内部采集接口不返回配置、数据库信息或认证细节。
            return ResponseEntity.status(503).body("UNAVAILABLE");
        }
    }
}
