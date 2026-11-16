package com.example.poi.agent.verify;

import com.example.poi.agent.intent.IntentCategory;
import com.example.poi.agent.intent.IntentResult;
import com.example.poi.agent.orchestrator.AgentResponse;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
public class AnswerVerifier {

    private static final Set<IntentCategory> DATA_INTENTS = EnumSet.of(
            IntentCategory.QUERY,
            IntentCategory.ROUTE
    );

    public AgentResponse verify(AgentResponse response, IntentResult intent) {
        if (response == null) {
            return AgentResponse.builder()
                    .answer("没有返回结果")
                    .success(false)
                    .build();
        }
        String answer = response.getAnswer();
        boolean success = response.isSuccess();

        if (answer == null || answer.isBlank()) {
            answer = "没有找到符合条件的结果，请换个地点或分类再试。";
            success = false;
        }
        if (answer.contains("NaN") || answer.contains("Infinity")) {
            answer = "结果包含无效数值，已中止展示，请换个参数再试。";
            success = false;
        }
        if (DATA_INTENTS.contains(intent.category()) && response.getSteps().isEmpty()) {
            answer = "没有获取到空间数据，请换个地点或分类再试。";
            success = false;
        }

        if (answer.equals(response.getAnswer()) && success == response.isSuccess()) {
            return response;
        }
        return response.toBuilder().answer(answer).success(success).build();
    }
}
