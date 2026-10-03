package com.blood.center.dto;

/**
 * 复测发起请求：检验项目 + 新结果 + 复测原因 + 检测人员。
 * resultValue 依项目而定：ALT/血红蛋白为数值字符串，其余为 NEGATIVE/POSITIVE。
 */
public record RetestRequest(String itemCode,
                            String resultValue,
                            String reason,
                            String operator) {
}
