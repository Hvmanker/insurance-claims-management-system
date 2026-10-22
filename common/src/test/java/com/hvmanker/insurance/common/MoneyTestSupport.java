package com.hvmanker.insurance.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class MoneyTestSupport {
    private MoneyTestSupport() {
    }

    public static void assertMoneyEqual(BigDecimal expected, BigDecimal actual) {
        assertEquals(
                0,
                expected.setScale(2, RoundingMode.UNNECESSARY)
                        .compareTo(actual.setScale(2, RoundingMode.UNNECESSARY)));
    }
}
