package com.example.poi.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "app.import.mode", havingValue = "kafka")
public class KafkaImportProducer {

    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper mapper;
    private final String topic;
    private final String retryTopic;
    private final String dlqTopic;

    public KafkaImportProducer(
            KafkaTemplate<String, String> kafka,
            ObjectMapper mapper,
            @Value("${app.import.topic:poi.import}") String topic,
            @Value("${app.import.retry-topic:poi.import.retry}") String retryTopic,
            @Value("${app.import.dlq-topic:poi.import.dlq}") String dlqTopic) {
        this.kafka = kafka;
        this.mapper = mapper;
        this.topic = topic;
        this.retryTopic = retryTopic;
        this.dlqTopic = dlqTopic;
    }

    public void send(ImportMessage message) {
        send(topic, message);
    }

    public void sendRetry(ImportMessage message) {
        send(retryTopic, message);
    }

    public void sendDlq(ImportMessage message) {
        send(dlqTopic, message);
    }

    private void send(String target, ImportMessage message) {
        try {
            kafka.send(target, String.valueOf(message.taskId()), mapper.writeValueAsString(message))
                    .get(5, TimeUnit.SECONDS);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法序列化导入消息", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("Kafka 发送失败: " + target, ex);
        }
    }
}
