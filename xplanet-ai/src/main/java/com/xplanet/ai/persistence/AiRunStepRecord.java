package com.xplanet.ai.persistence;

import com.xplanet.api.dto.AiCheckpointData;
import lombok.Data;

@Data
public class AiRunStepRecord {
    private Long id;
    private String runId;
    private String nodeName;
    private String inputHash;
    private Integer stateVersion;
    private String checkpointJson;
    private Long durationMs;

    public AiCheckpointData toCheckpoint() {
        return AiCheckpointData.builder()
                .runId(runId)
                .node(nodeName)
                .inputHash(inputHash)
                .stateVersion(stateVersion)
                .stateJson(checkpointJson)
                .durationMs(durationMs)
                .build();
    }
}
