package com.example.poi.controller;

import com.example.poi.service.ImportService;
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

    private final ImportService service;

    public ImportController(ImportService service) {
        this.service = service;
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
        service.process(taskId, tmp);
        return Map.of("code", 0, "message", "accepted", "taskId", taskId);
    }

    @GetMapping("/tasks/{id}")
    public Map<String, Object> task(@PathVariable long id) {
        return Map.of("code", 0, "message", "ok", "data", service.getTask(id));
    }
}
