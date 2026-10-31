package com.tejas.contentreview.caseservice.reliability;

import java.util.concurrent.TimeUnit;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaOutboxSender implements OutboxSender {

    private final KafkaTemplate<String, String> kafka;

    public KafkaOutboxSender(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void send(String topic, String key, String payload) throws Exception {
        kafka.send(topic, key, payload).get(10, TimeUnit.SECONDS);
    }
}
