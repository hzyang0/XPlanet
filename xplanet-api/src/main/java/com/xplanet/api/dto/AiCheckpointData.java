package com.xplanet.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiCheckpointData implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank
    @Size(max = 36)
    private String runId;
    @NotBlank
    @Size(max = 64)
    private String node;
    @NotBlank
    @Pattern(regexp = "[0-9a-f]{64}")
    private String inputHash;
    @Min(1)
    @Max(10)
    private Integer stateVersion;
    @NotBlank
    @Size(max = 2_000_000)
    private String stateJson;
    @Min(0)
    private Long durationMs;
}
