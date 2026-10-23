package com.lqbz.ops.service;

@lombok.EqualsAndHashCode()
@lombok.ToString()
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
public final class InspectionPlanSyncRequested {

    @com.fasterxml.jackson.annotation.JsonCreator()
    public InspectionPlanSyncRequested(@com.fasterxml.jackson.annotation.JsonProperty("planId") Long planId) {
        this.planId = planId;
    }

    private final Long planId;

    public Long planId() {
        return this.planId;
    }
}
