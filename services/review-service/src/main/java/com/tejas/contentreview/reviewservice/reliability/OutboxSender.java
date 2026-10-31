package com.tejas.contentreview.reviewservice.reliability;

/** Blocking send used by the outbox relay; returns normally only after the broker acknowledged the record. */
public interface OutboxSender {

    void send(String topic, String key, String payload) throws Exception;
}
