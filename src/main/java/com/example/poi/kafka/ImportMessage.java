package com.example.poi.kafka;

public record ImportMessage(long taskId, String path, String filename) {
}
