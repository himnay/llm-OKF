package com.llm.okf.models.model;

import java.time.Instant;

/** Result of one Hugging Face sync run — fetched/upserted counts plus files written by both OKF patterns. */
public record ModelSyncStatus(
        int fetched,
        int upserted,
        int materializedFiles,
        int queryFiles,
        Instant startedAt,
        Instant finishedAt,
        long durationMs,
        String error) {

    public static ModelSyncStatus success(int fetched, int upserted, int materializedFiles, int queryFiles,
                                          Instant startedAt, Instant finishedAt) {
        return new ModelSyncStatus(fetched, upserted, materializedFiles, queryFiles,
                startedAt, finishedAt, finishedAt.toEpochMilli() - startedAt.toEpochMilli(), null);
    }

    public static ModelSyncStatus failure(String error, Instant startedAt, Instant finishedAt) {
        return new ModelSyncStatus(0, 0, 0, 0,
                startedAt, finishedAt, finishedAt.toEpochMilli() - startedAt.toEpochMilli(), error);
    }
}
