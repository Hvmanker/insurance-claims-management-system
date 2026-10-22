package com.hvmanker.insurance.common.outbox;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

final class LockRecordingJdbcTemplate extends JdbcTemplate {

    private final List<Boolean> lockOutcomes;
    private final CountDownLatch releaseWhileLockHeld;
    private final AtomicBoolean releaseTriggered;

    LockRecordingJdbcTemplate(DataSource dataSource, List<Boolean> lockOutcomes) {
        this(dataSource, lockOutcomes, null, null);
    }

    LockRecordingJdbcTemplate(
            DataSource dataSource,
            List<Boolean> lockOutcomes,
            CountDownLatch releaseWhileLockHeld,
            AtomicBoolean releaseTriggered) {
        super(dataSource);
        this.lockOutcomes = lockOutcomes;
        this.releaseWhileLockHeld = releaseWhileLockHeld;
        this.releaseTriggered = releaseTriggered;
    }

    @Override
    public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
        T result = super.queryForObject(sql, requiredType, args);
        if (sql.contains("pg_try_advisory_xact_lock")) {
            boolean acquired = Boolean.TRUE.equals(result);
            lockOutcomes.add(acquired);
            if (!acquired
                    && releaseWhileLockHeld != null
                    && releaseTriggered != null
                    && releaseTriggered.compareAndSet(false, true)) {
                releaseWhileLockHeld.countDown();
            }
        }
        return result;
    }
}
