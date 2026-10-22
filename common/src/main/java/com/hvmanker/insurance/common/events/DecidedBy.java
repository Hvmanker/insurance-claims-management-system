package com.hvmanker.insurance.common.events;

public record DecidedBy(String userId, String role) {
    public static final DecidedBy AUTO_APPROVAL = new DecidedBy("system:auto-approval", "SYSTEM");
}
