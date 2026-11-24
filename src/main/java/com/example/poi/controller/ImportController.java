package com.example.poi.controller;

import com.example.poi.kafka.ImportMessage;
import com.example.poi.kafka.KafkaImportProducer;
import com.example.poi.service.ImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api/import")
public class ImportController {

    private static final Logger log = LoggerFactory.getLogger(ImportController.class);

    private final ImportService service;
    private final ObjectProvider<KafkaImportProducer> kafkaProducer;
    private final String importMode;

    public ImportController(ImportService service,
                            ObjectProvider<KafkaImportProducer> kafkaProducer,
                            @Value("${app.import.mode:local}") String importMode) {
        this.service = service;
        this.kafkaProducer = kafkaProducer;
        this.importMode = importMode;
    }

    @PostMapping(value = "/poi", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestParam("file") MultipartFile file)
            throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty file");
        }
        Path tmp = Files.createTempFile("poi-import-", ".csv");
        file.transferTo(tmp);
        long taskId = service.createTask(file.getOriginalFilename());
        KafkaImportProducer producer = kafkaProducer.getIfAvailable();
        if ("kafka".equalsIgnoreCase(importMode) && producer != null) {
            try {
                producer.send(new ImportMessage(taskId, tmp.toString(), file.getOriginalFilename()));
            } catch (Exception ex) {
                log.warn("Kafka 发送失败，回退本地异步导入: {}", ex.getMessage());
                service.process(taskId, tmp);
            }
        } else {
            service.process(taskId, tmp);
        }
        return Map.of("code", 0, "message", "accepted", "taskId", taskId);
    }

    @GetMapping("/tasks/{id}")
    public Map<String, Object> task(@PathVariable long id) {
        return Map.of("code", 0, "message", "ok", "data", service.getTask(id));
    }
}
