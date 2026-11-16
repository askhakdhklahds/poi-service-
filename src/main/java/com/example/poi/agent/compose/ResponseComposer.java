package com.example.poi.agent.compose;

import com.example.poi.agent.orchestrator.AgentResponse;
import org.springframework.stereotype.Component;

@Component
public class ResponseComposer {

    /**
     * 当前只有一个 Agent 返回，透传即可；保留这个扩展点，
     * 后续多 Agent 协作时在这里做合并、去重和冲突处理。
     */
    public AgentResponse compose(AgentResponse response) {
        if (response == null) {
            return AgentResponse.builder()
                    .answer("没有返回结果")
                    .success(false)
                    .build();
        }
        String answer = response.getAnswer() == null ? "" : response.getAnswer().trim();
        if (answer.isBlank()) {
            return response.toBuilder()
                    .answer("没有找到符合条件的结果。")
                    .success(false)
                    .build();
        }
        if (!answer.equals(response.getAnswer())) {
            return response.toBuilder().answer(answer).build();
        }
        return response;
    }
}
