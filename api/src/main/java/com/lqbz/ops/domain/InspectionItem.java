package com.lqbz.ops.domain;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "ops_inspection_item")
public class InspectionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long inspectionId;

    private String category;

    private String targetName;

    private String checkName;

    private String status;

    private String message;

    private Long durationMillis;

    protected InspectionItem() {
    }

    public InspectionItem(Long inspectionId, String category, String targetName, String checkName, String status, String message, Long durationMillis) {
        this.inspectionId = inspectionId;
        this.category = category;
        this.targetName = targetName;
        this.checkName = checkName;
        this.status = status;
        this.message = message;
        this.durationMillis = durationMillis;
    }

    public Long getId() {
        return id;
    }

    public Long getInspectionId() {
        return inspectionId;
    }

    public String getCategory() {
        return category;
    }

    public String getTargetName() {
        return targetName;
    }

    public String getCheckName() {
        return checkName;
    }

    public String getStatus() {
        return status;
    }

    public String getMessage() {
        return message;
    }

    public Long getDurationMillis() {
        return durationMillis;
    }
}
