package com.xplanet.api.request;

import lombok.Data;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;

@Data
public class CreateResearchTaskRequest {

    @NotBlank(message = "研究问题不能为空")
    @Size(max = 2000, message = "研究问题不能超过2000字符")
    private String question;

    @Pattern(regexp = "offline-demo|deepseek-tools", message = "执行模式只支持 offline-demo 或 deepseek-tools")
    private String provider = "offline-demo";

    @Min(value = 1, message = "来源上限至少为1")
    @Max(value = 20, message = "来源上限不能超过20")
    private Integer maxSources = 5;

    @Min(value = 1, message = "工具调用上限至少为1")
    @Max(value = 50, message = "工具调用上限不能超过50")
    private Integer maxToolCalls = 10;

    @Min(value = 1000, message = "Token预算至少为1000")
    @Max(value = 100000, message = "Token预算不能超过100000")
    private Integer maxTokens = 8000;

    @Min(value = 30, message = "任务超时至少为30秒")
    @Max(value = 3600, message = "任务超时不能超过3600秒")
    private Integer deadlineSeconds = 300;
}
