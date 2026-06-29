package com.llm.okf.scheduler;

import com.llm.okf.config.OkfProperties;
import com.llm.okf.service.GitHubSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OkfSyncScheduler {

    private final GitHubSyncService syncService;
    private final OkfProperties properties;

    /** Triggers a one-time sync in a background thread after startup — async so it does not block readiness state. */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (properties.sync().syncOnStartup()) {
            log.info("Startup sync triggered for: {}", properties.sync().githubUrl());
            syncService.sync();
        }
    }

    /**
     * Periodic sync — runs every {@code app.okf.sync.interval-ms} (default 1 hour).
     * ShedLock prevents concurrent execution across multiple instances.
     */
    @Scheduled(
            initialDelayString = "${app.okf.sync.interval-ms:3600000}",
            fixedDelayString   = "${app.okf.sync.interval-ms:3600000}"
    )
    @SchedulerLock(name = "okf-github-sync", lockAtMostFor = "PT30M", lockAtLeastFor = "PT5M")
    public void scheduledSync() {
        if (!properties.sync().enabled()) return;
        log.info("Scheduled sync triggered");
        syncService.sync();
    }
}
