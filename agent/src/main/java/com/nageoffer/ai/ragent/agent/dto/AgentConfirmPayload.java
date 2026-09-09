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

package com.nageoffer.ai.ragent.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * SSE confirm 事件载荷，前端据此展示确认卡片，messageId 用于后续确认请求
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentConfirmPayload(String messageId, String title, List<AgentConfirmCall> calls) {
}
