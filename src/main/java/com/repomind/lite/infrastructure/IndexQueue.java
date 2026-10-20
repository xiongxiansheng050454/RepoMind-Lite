package com.repomind.lite.infrastructure;

import org.slf4j.*;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class IndexQueue {
    public static final String QUEUE = "repomind.index";
    private static final Logger log = LoggerFactory.getLogger(IndexQueue.class);
    private final IndexStore store;
    private final RabbitTemplate rabbit;

    public IndexQueue(IndexStore store, RabbitTemplate rabbit) {
        this.store = store;
        this.rabbit = rabbit;
    }

    @Bean
    Queue indexQueueBean() {
        return new Queue(QUEUE, true, false, false);
    }

    @Scheduled(fixedDelayString = "${repomind.repositories.recovery-delay-ms:5000}")
    public void publishPending() {
        try {
            store.recover();
            for (var jobId : store.pendingMessages()) {
                rabbit.convertAndSend("", QUEUE, jobId.toString(), message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    return message;
                });
                store.published(jobId);
            }
        } catch (Exception ex) {
            log.warn("Index outbox will retry ({})", ex.getClass().getSimpleName());
        }
    }
}
