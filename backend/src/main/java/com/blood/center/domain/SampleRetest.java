package com.blood.center.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 标本复测轮次：检测人员针对某一检验项目发起的一轮复测。
 * 原直接录入结果保留在 test_sample 各列，复测只新增轮次行，绝不覆盖；
 * 同一 (标本, 项目, 轮次) 唯一 —— 并发发起同一项目复测时只有一行能落库。
 */
@Entity
@Table(name = "sample_retest")
@Getter
@Setter
public class SampleRetest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sample_id")
    private TestSample sample;

    /** ALT / HB / HBV / HCV / HIV / SYPHILIS */
    @Column(name = "item_code", nullable = false, length = 16)
    private String itemCode;

    /** 复测轮次：同一项目从 1 开始递增 */
    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** 新结果：数值（ALT/血红蛋白）或 NEGATIVE/POSITIVE */
    @Column(name = "result_value", nullable = false, length = 32)
    private String resultValue;

    /** QUALIFIED 合格 / UNQUALIFIED 不合格 */
    @Column(name = "conclusion", nullable = false, length = 16)
    private String conclusion;

    /** 复测原因 */
    @Column(name = "reason", nullable = false, length = 255)
    private String reason;

    /** 检测人员 */
    @Column(name = "operator", nullable = false, length = 32)
    private String operator;

    /** 复核定夺：TRUE 采用 / FALSE 拒绝 / NULL 待复核 */
    @Column(name = "adopted")
    private Boolean adopted;

    /** 复核人采用或拒绝复测结果的理由 */
    @Column(name = "adopt_reason", length = 255)
    private String adoptReason;

    /** 复核人 */
    @Column(name = "reviewer", length = 32)
    private String reviewer;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
