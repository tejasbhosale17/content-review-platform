package com.tejas.contentreview.contentservice.projection;

import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload;
import com.tejas.contentreview.contracts.events.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class ReviewResultListener {

    private final ContentProjectionService projection;

    public ReviewResultListener(ContentProjectionService projection) {
        this.projection = projection;
    }

    @KafkaListener(topics = Topics.REVIEW_COMPLETED, groupId = Topics.GROUP_CONTENT_PROJECTION)
    public void onReviewCompleted(ConsumerRecord<String, String> record) {
        projection.apply(EventCodec.fromJson(record.value(), ReviewCompletedPayload.class));
    }
}
