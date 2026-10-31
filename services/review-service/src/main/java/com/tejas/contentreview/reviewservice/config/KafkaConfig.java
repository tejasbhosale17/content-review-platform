package com.tejas.contentreview.reviewservice.config;

import com.tejas.contentreview.contracts.events.EventFormatException;
import com.tejas.contentreview.contracts.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    private static final String DLT = Topics.deadLetter(Topics.REVIEW_REQUESTED, Topics.GROUP_REVIEW_WORKERS);

    @Bean
    NewTopic reviewRequestedTopic() {
        return TopicBuilder.name(Topics.REVIEW_REQUESTED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic reviewCompletedTopic() {
        return TopicBuilder.name(Topics.REVIEW_COMPLETED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic reviewRequestedDeadLetterTopic() {
        return TopicBuilder.name(DLT).partitions(1).replicas(1).build();
    }

    /**
     * Bounded retries with backoff, then the record goes to the group's dead-letter topic with exception headers.
     * If the dead-letter publish itself fails the record is redelivered rather than dropped.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> template, MeterRegistry registry) {
        var dlq = new DeadLetterPublishingRecoverer(template, (record, ex) -> new TopicPartition(DLT, -1));
        ConsumerRecordRecoverer recoverer = (ConsumerRecord<?, ?> record, Exception ex) -> {
            dlq.accept(record, ex);
            registry.counter("kafka.consumer.dead_lettered", "group", Topics.GROUP_REVIEW_WORKERS, "topic", record.topic()).increment();
        };
        ExponentialBackOff backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxInterval(2_000);
        backOff.setMaxAttempts(5);
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(EventFormatException.class);
        handler.setRetryListeners(new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
                registry.counter("kafka.consumer.retries", "group", Topics.GROUP_REVIEW_WORKERS, "topic", record.topic()).increment();
            }
        });
        return handler;
    }
}
