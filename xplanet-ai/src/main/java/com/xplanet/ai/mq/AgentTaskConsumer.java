package com.xplanet.ai.mq;

import com.xplanet.ai.service.AgentTaskExecutionService;
import com.xplanet.api.dto.AiTaskCommand;
import com.xplanet.common.constant.MqTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.TOPIC_AI_TASK,
        consumerGroup = MqTopics.GROUP_AI_TASK_CONSUMER,
        selectorExpression = "*")
public class AgentTaskConsumer implements RocketMQListener<AiTaskCommand> {

    private final AgentTaskExecutionService executionService;

    @Override
    public void onMessage(AiTaskCommand command) {
        log.info("received AI command, eventId={}, type={}, taskId={}, runId={}",
                command.getEventId(), command.getEventType(), command.getTaskId(), command.getRunId());
        executionService.handle(command);
    }
}
