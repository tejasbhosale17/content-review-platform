package com.tejas.contentreview.reviewservice.config;

import com.tejas.contentreview.contracts.events.EventFormatException;
import com.tejas.contentreview.contracts.events.Topics;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    NewTopic reviewRequestedTopic() {
        return TopicBuilder.name(Topics.REVIEW_REQUESTED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic reviewCompletedTopic() {
        return TopicBuilder.name(Topics.REVIEW_COMPLETED).partitions(3).replicas(1).build();
    }

    /**
     * Interim: bounded retries with backoff, after which the record is skipped and logged.
     * Dead-letter topics replace the skip in the reliability stage.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxInterval(2_000);
        backOff.setMaxAttempts(5);
        DefaultErrorHandler handler = new DefaultErrorHandler(backOff);
        handler.addNotRetryableExceptions(EventFormatException.class);
        return handler;
    }
}
