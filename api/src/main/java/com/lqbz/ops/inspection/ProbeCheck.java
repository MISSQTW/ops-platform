package com.lqbz.ops.inspection;

public record ProbeCheck(String category, String name, String target, boolean passed, String message, long durationMillis) {}
