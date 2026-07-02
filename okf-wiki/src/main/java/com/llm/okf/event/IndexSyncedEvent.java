package com.llm.okf.event;

import org.springframework.context.ApplicationEvent;

public class IndexSyncedEvent extends ApplicationEvent {
    public IndexSyncedEvent(Object source) {
        super(source);
    }
}
