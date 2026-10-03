package com.blood.center.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 标本检验视图：6 个检验项目的当前有效结果 / 当前判定 / 复核人 / 状态 / 复测轮次。
 * items 展示各项目「当前有效结果」（初检或最后一轮未被拒绝的复测），
 * retests 按轮次展示全部复测记录，原初检结果始终保留在标本主记录上不被覆盖。
 */
public record SampleVO(Long id,
                       String barcode,
                       Long collectionId,
                       String collectNo,
                       String donorName,
                       String bloodType,
                       BigDecimal altValue,
                       BigDecimal hbValue,
                       String hbvResult,
                       String hcvResult,
                       String hivResult,
                       String syphilisResult,
                       String reviewer,
                       String status,
                       String statusName,
                       String currentVerdict,
                       LocalDateTime testedAt,
                       LocalDateTime releasedAt,
                       List<ItemVO> items,
                       List<RetestRoundVO> retests) {

    /**
     * 单个检验项目的当前有效结果展示（ALT / 血红蛋白 / HBV / HCV / HIV / 梅毒）
     */
    public record ItemVO(String code, String name, String value, String unit, String conclusion, String source) {
    }

    /**
     * 一轮复测记录：current 标记该项目当前有效结果所在的轮次。
     */
    public record RetestRoundVO(Long id, String itemCode, String itemName, Integer roundNo,
                                String value, String unit, String conclusion, String conclusionName,
                                String reason, String operator,
                                Boolean adopted, String adoptReason, String reviewer,
                                LocalDateTime reviewedAt, LocalDateTime createdAt, boolean current) {
    }
}
