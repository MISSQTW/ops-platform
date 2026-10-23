package com.lqbz.ops.domain;

import java.time.LocalDateTime;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "ops_monitoring_sync_lease")
public class MonitoringSyncLease {

    @Id
    private String id;

    private LocalDateTime leaseUntil;

    private String ownerToken;

    protected MonitoringSyncLease() {
    }

    public MonitoringSyncLease(String id) {
        this.id = id;
    }
}
