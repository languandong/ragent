/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.nageoffer.ai.ragent.rag.core.guidance;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nageoffer.ai.ragent.framework.convention.ChatMessage;
import com.nageoffer.ai.ragent.framework.convention.ChatRequest;
import com.nageoffer.ai.ragent.infra.chat.LLMService;
import com.nageoffer.ai.ragent.infra.util.LLMResponseCleaner;
import com.nageoffer.ai.ragent.rag.core.intent.IntentNode;
import com.nageoffer.ai.ragent.rag.core.intent.NodeScore;
import com.nageoffer.ai.ragent.rag.core.prompt.PromptTemplateLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.nageoffer.ai.ragent.rag.constant.RAGConstant.GUIDANCE_AMBIGUITY_CHECK_PROMPT_PATH;

/**
 * LLM 歧义确认器
 * 仅在规则层无法明确判断时调用，通过 LLM 语义理解确认是否存在品类歧义
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AmbiguityLLMChecker {

    private final LLMService llmService;
    private final PromptTemplateLoader promptTemplateLoader;

    /**
     * 调用 LLM 确认是否存在歧义
     */
    public boolean checkAmbiguity(String question, List<NodeScore> ranked) {
        // 只把当前问题和候选路径/分数交给 LLM，不重新执行意图分类。
        String candidatesText = buildCandidatesText(ranked);
        String prompt = promptTemplateLoader.render(
                GUIDANCE_AMBIGUITY_CHECK_PROMPT_PATH,
                Map.of(
                        "question", question,
                        "candidates", candidatesText
                )
        );

        ChatRequest request = ChatRequest.builder()
                .messages(List.of(
                        ChatMessage.user(prompt)
                ))
                .temperature(0.1D)
                .topP(0.3D)
                .thinking(false)
                .build();

        try {
            String raw = llmService.chat(request);
            String cleaned = LLMResponseCleaner.stripMarkdownCodeFence(raw);
            JsonElement root = JsonParser.parseString(cleaned);

            if (!root.isJsonObject()) {
                log.warn("歧义确认 LLM 返回非 JSON 对象: {}", raw);
                // 无法可靠判断时优先澄清，避免带着错误意图继续检索。
                return true;
            }

            JsonObject obj = root.getAsJsonObject();
            if (obj.has("ambiguous")) {
                boolean ambiguous = obj.get("ambiguous").getAsBoolean();
                String reason = obj.has("reason") ? obj.get("reason").getAsString() : "";
                log.info("LLM 歧义确认结果: ambiguous={}, reason={}, question={}", ambiguous, reason, question);
                return ambiguous;
            }

            log.warn("歧义确认 LLM 返回缺少 ambiguous 字段: {}", raw);
            // 返回格式不完整时采用保守策略：触发澄清。
            return true;
        } catch (Exception e) {
            log.warn("歧义确认 LLM 调用失败, 降级为触发澄清, question={}", question, e);
            return true;
        }
    }

    private String buildCandidatesText(List<NodeScore> ranked) {
        return ranked.stream()
                .map(ns -> {
                    IntentNode node = ns.getNode();
                    String systemPath = node.getFullPath() != null ? node.getFullPath() : node.getName();
                    return String.format("- 品类ID: %s, 名称: %s, 路径: %s, 分数: %.2f",
                            node.getId(), node.getName(), systemPath, ns.getScore());
                })
                .collect(Collectors.joining("\n"));
    }
}
