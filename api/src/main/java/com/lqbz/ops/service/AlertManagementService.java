package com.lqbz.ops.service;

import com.lqbz.ops.domain.Alert;
import com.lqbz.ops.domain.AlertHandlingEvent;
import com.lqbz.ops.domain.AlertHandlingEventRepository;
import com.lqbz.ops.domain.AlertRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertManagementService {

    private final AlertRepository alerts;

    private final AlertHandlingEventRepository events;

    public AlertManagementService(AlertRepository alerts, AlertHandlingEventRepository events) {
        this.alerts = alerts;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<Alert> list(Long projectId, Long planId, Long assetId, String externalLifecycle, String handlingStatus) {
        return alerts.findAll(Sort.by(Sort.Direction.DESC, "occurredAt")).stream().filter(alert -> projectId == null || projectId.equals(alert.getProjectId())).filter(alert -> planId == null || planId.equals(alert.getInspectionPlanId())).filter(alert -> assetId == null || assetId.equals(alert.getAssetId())).filter(alert -> blank(externalLifecycle) || externalLifecycle.equalsIgnoreCase(alert.getExternalLifecycle())).filter(alert -> blank(handlingStatus) || handlingStatus.equalsIgnoreCase(alert.getHandlingStatus())).collect(java.util.stream.Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Alert get(Long id) {
        return alerts.findById(id).orElseThrow(() -> new NoSuchElementException("告警不存在"));
    }

    @Transactional(readOnly = true)
    public List<AlertHandlingEvent> events(Long id) {
        if (!alerts.existsById(id))
            throw new NoSuchElementException("告警不存在");
        return events.findByAlertIdOrderByOccurredAtAscIdAsc(id);
    }

    @Transactional
    public Alert acknowledge(Long id, String operator, String note) {
        Alert alert = get(id);
        String fromStatus = alert.getHandlingStatus();
        LocalDateTime now = LocalDateTime.now();
        alert.acknowledge(operator, note, now);
        record(alert, "ACKNOWLEDGED", fromStatus, operator, note, now);
        return alert;
    }

    @Transactional
    public Alert addNote(Long id, String operator, String note) {
        Alert alert = get(id);
        LocalDateTime now = LocalDateTime.now();
        String normalizedNote = requireNote(note);
        record(alert, "NOTE_ADDED", alert.getHandlingStatus(), operator, normalizedNote, now);
        return alert;
    }

    @Transactional
    public Alert close(Long id, String operator, String note) {
        Alert alert = get(id);
        String fromStatus = alert.getHandlingStatus();
        LocalDateTime now = LocalDateTime.now();
        alert.close(operator, note, now);
        record(alert, "CLOSED", fromStatus, operator, note, now);
        return alert;
    }

    private void record(Alert alert, String action, String fromStatus, String operator, String note, LocalDateTime now) {
        events.save(new AlertHandlingEvent(alert.getId(), action, fromStatus, alert.getHandlingStatus(), operator, note, now));
    }

    private String requireNote(String note) {
        if (blank(note))
            throw new IllegalArgumentException("处置备注不能为空");
        return note.trim();
    }

    private boolean blank(String value) {
        return value == null || com.lqbz.ops.compat.Java8.isBlank(value);
    }
}
