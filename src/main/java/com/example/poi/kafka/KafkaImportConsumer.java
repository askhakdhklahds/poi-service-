package com.example.poi.kafka;

import com.example.poi.service.ImportService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@ConditionalOnProperty(name = "app.import.mode", havingValue = "kafka")
public class KafkaImportConsumer {

    private final ImportService service;
    private final KafkaImportProducer producer;
    private final ObjectMapper mapper;

    public KafkaImportConsumer(ImportService service, KafkaImportProducer producer, ObjectMapper mapper) {
        this.service = service;
        this.producer = producer;
        this.mapper = mapper;
    }

    @KafkaListener(
            topics = "${app.import.topic:poi.import}",
            groupId = "${app.import.group:poi-import-worker}")
    public void consume(String payload, Acknowledgment ack) throws Exception {
        ImportMessage message = mapper.readValue(payload, ImportMessage.class);
        // 第一次失败保留临时文件，交给 retry topic 再处理。
        boolean success = service.processNow(message.taskId(), Path.of(message.path()), false);
        if (!success) {
            producer.sendRetry(message);
        }
        ack.acknowledge();
    }

    @KafkaListener(
            topics = "${app.import.retry-topic:poi.import.retry}",
            groupId = "${app.import.group:poi-import-worker}-retry")
    public void consumeRetry(String payload, Acknowledgment ack) throws Exception {
        ImportMessage message = mapper.readValue(payload, ImportMessage.class);
        // 第二次仍失败则删除临时文件并进入死信队列。
        boolean success = service.processNow(message.taskId(), Path.of(message.path()), true);
        if (!success) {
            producer.sendDlq(message);
        }
        ack.acknowledge();
    }
}
