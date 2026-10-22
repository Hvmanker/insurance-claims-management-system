package com.hvmanker.insurance.common.outbox;

import java.util.UUID;

public final class SyntheticIds {

    public static final UUID AGGREGATE_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    public static final UUID AGGREGATE_B = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private SyntheticIds() {
    }

    public static UUID eventId(int index) {
        if (index < 1 || index > 50) {
            throw new IllegalArgumentException("event index must be 1..50");
        }
        return UUID.fromString(String.format("00000000-0000-0000-0000-0000000001%02d", index));
    }
}
