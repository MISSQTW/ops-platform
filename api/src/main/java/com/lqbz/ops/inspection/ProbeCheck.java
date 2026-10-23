package com.lqbz.ops.inspection;

@lombok.EqualsAndHashCode()
@lombok.ToString()
@com.fasterxml.jackson.annotation.JsonAutoDetect(fieldVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.ANY, getterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE, isGetterVisibility = com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility.NONE)
public final class ProbeCheck {

    @com.fasterxml.jackson.annotation.JsonCreator()
    public ProbeCheck(@com.fasterxml.jackson.annotation.JsonProperty("category") String category, @com.fasterxml.jackson.annotation.JsonProperty("name") String name, @com.fasterxml.jackson.annotation.JsonProperty("target") String target, @com.fasterxml.jackson.annotation.JsonProperty("passed") boolean passed, @com.fasterxml.jackson.annotation.JsonProperty("message") String message, @com.fasterxml.jackson.annotation.JsonProperty("durationMillis") long durationMillis) {
        this.category = category;
        this.name = name;
        this.target = target;
        this.passed = passed;
        this.message = message;
        this.durationMillis = durationMillis;
    }

    private final String category;

    public String category() {
        return this.category;
    }

    private final String name;

    public String name() {
        return this.name;
    }

    private final String target;

    public String target() {
        return this.target;
    }

    private final boolean passed;

    public boolean passed() {
        return this.passed;
    }

    private final String message;

    public String message() {
        return this.message;
    }

    private final long durationMillis;

    public long durationMillis() {
        return this.durationMillis;
    }
}
