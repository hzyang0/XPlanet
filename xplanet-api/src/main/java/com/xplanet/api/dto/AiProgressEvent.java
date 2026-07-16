package com.xplanet.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;
import java.io.Serializable;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiProgressEvent implements Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank
    private String runId;
    @NotBlank
    @Size(max = 64)
    private String node;
    @NotBlank
    @Size(max = 32)
    private String status;
    @Size(max = 500)
    private String message;
    @Min(0)
    @Max(100)
    private Integer progress;
    private Long timestamp;
}
