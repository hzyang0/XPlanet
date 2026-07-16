package com.xplanet.api.request;

import lombok.Data;

import javax.validation.constraints.Size;

@Data
public class ReviewReportRequest {
    @Size(max = 500, message = "报告标题不能超过500字符")
    private String title;
    @Size(max = 200000, message = "报告正文不能超过200000字符")
    private String content;
}
