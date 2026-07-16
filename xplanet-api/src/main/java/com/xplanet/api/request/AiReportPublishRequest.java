package com.xplanet.api.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

@Data
public class AiReportPublishRequest {
    @NotNull
    private Long reportId;
    @NotNull
    private Long authorId;
    @NotBlank
    @Size(max = 200)
    private String title;
    @NotBlank
    private String content;
    @Size(max = 255)
    private String tags = "ai,research";
}
