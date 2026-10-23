package com.lqbz.ops.web;

import com.lqbz.ops.auth.SessionService;
import com.lqbz.ops.domain.Alert;
import com.lqbz.ops.domain.AlertHandlingEvent;
import com.lqbz.ops.service.AlertManagementService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alerts")
public class AlertController {

    private final AlertManagementService alertManagement;

    private final SessionService sessions;

    public AlertController(AlertManagementService alertManagement, SessionService sessions) {
        this.alertManagement = alertManagement;
        this.sessions = sessions;
    }

    @GetMapping
    public List<Alert> list(@RequestParam(required = false) Long projectId, @RequestParam(required = false) Long planId, @RequestParam(required = false) Long assetId, @RequestParam(required = false) String externalLifecycle, @RequestParam(required = false) String handlingStatus) {
        return alertManagement.list(projectId, planId, assetId, externalLifecycle, handlingStatus);
    }

    @GetMapping("/{id}")
    public Alert get(@PathVariable Long id) {
        return alertManagement.get(id);
    }

    @GetMapping("/{id}/events")
    public List<AlertHandlingEvent> events(@PathVariable Long id) {
        return alertManagement.events(id);
    }

    @PostMapping("/{id}/acknowledge")
    public Alert acknowledge(@PathVariable Long id, @RequestBody(required = false) HandlingRequest request) {
        return alertManagement.acknowledge(id, sessions.displayName(), request == null ? null : request.note());
    }

    @PostMapping("/{id}/notes")
    public Alert addNote(@PathVariable Long id, @RequestBody HandlingRequest request) {
        return alertManagement.addNote(id, sessions.displayName(), request.note());
    }

    @PostMapping("/{id}/close")
    public Alert close(@PathVariable Long id, @RequestBody(required = false) HandlingRequest request) {
        return alertManagement.close(id, sessions.displayName(), request == null ? null : request.note());
    }

    @lombok.EqualsAndHashCode()
    @lombok.ToString()
    @com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
    public final static class HandlingRequest {

        @com.fasterxml.jackson.annotation.JsonCreator()
        public HandlingRequest(@com.fasterxml.jackson.annotation.JsonProperty("note") String note) {
            this.note = note;
        }

        private final String note;

        public String note() {
            return this.note;
        }
    }
}
