package com.llm.okf.models.scheduler;

import com.llm.okf.models.config.LlmModelsProperties;
import com.llm.okf.models.service.HuggingFaceSyncService;
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
public class LlmModelsScheduler {

    private final HuggingFaceSyncService syncService;
    private final LlmModelsProperties properties;

    /** Triggers a one-time catalog sync in a background thread after startup — async so it does not block readiness state. */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void syncOnStartup() {
        if (properties.syncOnStartup()) {
            log.info("Startup LLM model catalog sync triggered");
            syncService.sync();
        }
    }

    /**
     * Periodic catalog sync — runs every {@code app.okf.llm-models.interval-ms} (default 24 h).
     * ShedLock prevents concurrent execution across multiple instances.
     */
    @Scheduled(
            initialDelayString = "${app.okf.llm-models.interval-ms:86400000}",
            fixedDelayString   = "${app.okf.llm-models.interval-ms:86400000}"
    )
    @SchedulerLock(name = "okf-hf-models-sync", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")
    public void scheduledSync() {
        if (!properties.enabled()) return;
        log.info("Scheduled LLM model catalog sync triggered");
        syncService.sync();
    }
}
