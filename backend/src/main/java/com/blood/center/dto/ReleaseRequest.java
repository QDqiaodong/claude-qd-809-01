package com.blood.center.dto;

/**
 * 标本放行请求：必须指定复核人；
 * 标本存在待复核的复测结果时，必须给出采用/拒绝的定夺（retestAdopt）并记录理由（retestReason）。
 */
public record ReleaseRequest(String reviewer,
                             Boolean retestAdopt,
                             String retestReason) {

    public ReleaseRequest(String reviewer) {
        this(reviewer, null, null);
    }
}
