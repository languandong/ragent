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

package com.nageoffer.ai.ragent.mcp.executor;

import cn.hutool.core.util.StrUtil;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * 各 executor 共用的取参与结果封装
 */
final class McpToolResults {

    private McpToolResults() {
    }

    static CallToolResult success(String text) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(text)))
                .isError(false)
                .build();
    }

    static CallToolResult error(String message) {
        return CallToolResult.builder()
                .content(List.of(new TextContent(message)))
                .isError(true)
                .build();
    }

    static Map<String, Object> args(CallToolRequest request) {
        return request.arguments() != null ? request.arguments() : Map.of();
    }

    static LocalDate parseDate(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return LocalDate.parse(StrUtil.trim(value));
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
