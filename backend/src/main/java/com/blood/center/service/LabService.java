package com.blood.center.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.blood.center.common.BizException;
import com.blood.center.common.BloodDict;
import com.blood.center.domain.CollectionRecord;
import com.blood.center.domain.PreparationBatch;
import com.blood.center.domain.SampleRetest;
import com.blood.center.domain.TestSample;
import com.blood.center.dto.PreparationSummaryVO;
import com.blood.center.dto.PreparationVO;
import com.blood.center.dto.ReleaseRequest;
import com.blood.center.dto.RetestRequest;
import com.blood.center.dto.SampleVO;
import com.blood.center.dto.TestResultRequest;
import com.blood.center.repository.PreparationBatchRepository;
import com.blood.center.repository.SampleRetestRepository;
import com.blood.center.repository.TestSampleRepository;

/**
 * 标本检验与成分制备：待检 → 检测中 → 已放行（→ 领域事件自动生成制备单）/ 报废。
 *
 * 复测闭环的存储选型：保留现有直接录入记录并与复测轮次兼容 ——
 * 六项初检结果仍落在 test_sample 各列（历史标本的判定与放行口径完全不变），
 * 复测只向 sample_retest 追加轮次行，绝不覆盖初检；
 * 各项目「当前有效结果」= 初检结果被该项目最后一轮未被复核拒绝的复测覆盖后的值，
 * 放行判定一律以该有效结果为准。
 */
@Service
public class LabService {

    private static final DateTimeFormatter SEQ_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final TestSampleRepository sampleRepository;
    private final PreparationBatchRepository preparationRepository;
    private final SampleRetestRepository retestRepository;

    public LabService(TestSampleRepository sampleRepository, PreparationBatchRepository preparationRepository,
                      SampleRetestRepository retestRepository) {
        this.sampleRepository = sampleRepository;
        this.preparationRepository = preparationRepository;
        this.retestRepository = retestRepository;
    }

    // ---------------- 标本检验 ----------------

    @Transactional(readOnly = true)
    public List<SampleVO> listSamples(String bloodType) {
        List<TestSample> samples = bloodType == null || bloodType.isBlank()
                ? sampleRepository.findAll()
                : sampleRepository.findByBloodTypeOrderByIdAsc(bloodType);
        Map<Long, List<SampleRetest>> roundsBySample = roundsBySample(samples);
        List<SampleVO> vos = new ArrayList<>();
        for (TestSample sample : samples) {
            vos.add(toVO(sample, roundsBySample.getOrDefault(sample.getId(), List.of())));
        }
        return vos;
    }

    /**
     * 录入检验结果：ALT / 血红蛋白 / HBV / HCV / HIV / 梅毒；任一不合格直接判为报废。
     * 已存在复测轮次的标本禁止再直改初检结果（原结果不得覆盖），只能走复测流程。
     */
    @Transactional
    public SampleVO inputResult(Long id, TestResultRequest request) {
        TestSample sample = getSample(id);
        if ("RELEASED".equals(sample.getStatus())) {
            throw new BizException("标本 " + sample.getBarcode() + " 已放行，不能再修改检验结果");
        }
        if (retestRepository.countBySampleId(sample.getId()) > 0) {
            throw new BizException("标本 " + sample.getBarcode() + " 已存在复测记录，初检结果不得覆盖，请通过复测流程修正");
        }
        if (request.altValue() == null || request.hbValue() == null
                || request.hbvResult() == null || request.hcvResult() == null
                || request.hivResult() == null || request.syphilisResult() == null) {
            throw new BizException("ALT、血红蛋白、HBV、HCV、HIV、梅毒 六项结果必须全部录入");
        }
        sample.setAltValue(request.altValue());
        sample.setHbValue(request.hbValue());
        sample.setHbvResult(request.hbvResult());
        sample.setHcvResult(request.hcvResult());
        sample.setHivResult(request.hivResult());
        sample.setSyphilisResult(request.syphilisResult());
        sample.setTestedAt(LocalDateTime.now());
        if (request.operator() != null && !request.operator().isBlank()) {
            sample.setReviewer(request.operator());
        }
        String unqualified = unqualifiedReason(effectiveResults(sample, List.of()), sample.donorGender());
        if (unqualified != null) {
            sample.setStatus("SCRAPPED");
        } else {
            sample.setStatus("TESTING");
        }
        return toVO(sampleRepository.save(sample), List.of());
    }

    /**
     * 发起复测：检测中或因单项不合格报废的标本，对某一项目新增一轮复测结果。
     * 原初检结果保留在 test_sample 各列不被覆盖；已放行标本不得复测。
     * 并发同一项目复测依赖 (sample_id, item_code, round_no) 唯一键：只有一行能落库，其余报业务异常。
     */
    @Transactional
    public SampleVO retest(Long id, RetestRequest request) {
        TestSample sample = getSample(id);
        if ("RELEASED".equals(sample.getStatus())) {
            throw new BizException("标本 " + sample.getBarcode() + " 已放行，不得复测");
        }
        if (!"TESTING".equals(sample.getStatus()) && !"SCRAPPED".equals(sample.getStatus())) {
            throw new BizException("标本 " + sample.getBarcode() + " 当前状态为「"
                    + BloodDict.sampleStatusName(sample.getStatus()) + "」，只有检测中或报废的标本可以发起复测");
        }
        String itemCode = request.itemCode() == null ? "" : request.itemCode().trim().toUpperCase();
        if (!BloodDict.isValidTestItem(itemCode)) {
            throw new BizException("未知的检验项目：" + request.itemCode());
        }
        if (initialValue(sample, itemCode) == null) {
            throw new BizException("标本 " + sample.getBarcode() + " 的「" + BloodDict.testItemName(itemCode)
                    + "」尚无初检结果，请先录入检验结果");
        }
        if (request.reason() == null || request.reason().isBlank()) {
            throw new BizException("发起复测必须填写复测原因");
        }
        if (request.operator() == null || request.operator().isBlank()) {
            throw new BizException("发起复测必须填写检测人员");
        }
        String resultValue = normalizeResultValue(itemCode, request.resultValue());
        String conclusion = conclude(itemCode, resultValue, sample.donorGender());

        SampleRetest round = new SampleRetest();
        round.setSample(sample);
        round.setItemCode(itemCode);
        round.setRoundNo(retestRepository.maxRoundNo(sample.getId(), itemCode) + 1);
        round.setResultValue(resultValue);
        round.setConclusion(conclusion);
        round.setReason(request.reason().trim());
        round.setOperator(request.operator().trim());
        round.setCreatedAt(LocalDateTime.now());
        try {
            retestRepository.saveAndFlush(round);
        } catch (DataIntegrityViolationException e) {
            throw new BizException("「" + BloodDict.testItemName(itemCode) + "」的复测结果刚被他人提交，请刷新查看最新轮次");
        }

        // 按各项目最后一次有效结果重算标本状态：全部合格回到检测中，任一不合格即报废
        List<SampleRetest> rounds = retestRepository.findBySampleIdOrderByIdAsc(sample.getId());
        String unqualified = unqualifiedReason(effectiveResults(sample, rounds), sample.donorGender());
        sample.setStatus(unqualified == null ? "TESTING" : "SCRAPPED");
        sampleRepository.save(sample);
        return toVO(sample, rounds);
    }

    /**
     * 标本放行：必须有复核人、六项结果齐全且合格；放行后触发成分制备（领域事件）。
     * 存在待复核的复测结果时，复核人必须明确采用或拒绝并记录理由；
     * 放行判定依据各项目最后一次有效结果（初检 + 未被拒绝的最后一轮复测）。
     */
    @Transactional
    public SampleVO release(Long id, ReleaseRequest request) {
        TestSample sample = getSample(id);
        if ("RELEASED".equals(sample.getStatus())) {
            throw new BizException("标本 " + sample.getBarcode() + " 已放行，请勿重复操作");
        }
        if (!"TESTING".equals(sample.getStatus())) {
            throw new BizException("标本 " + sample.getBarcode() + " 当前状态为「"
                    + BloodDict.sampleStatusName(sample.getStatus()) + "」，只有检测中的标本可以放行");
        }
        if (request.reviewer() == null || request.reviewer().isBlank()) {
            throw new BizException("放行前必须选择复核人");
        }
        List<SampleRetest> rounds = retestRepository.findBySampleIdOrderByIdAsc(sample.getId());
        List<SampleRetest> pending = rounds.stream().filter(r -> r.getAdopted() == null).collect(Collectors.toList());
        if (!pending.isEmpty()) {
            if (request.retestAdopt() == null) {
                throw new BizException("标本 " + sample.getBarcode() + " 存在 " + pending.size()
                        + " 条待复核的复测结果，放行时必须明确采用或拒绝");
            }
            if (request.retestReason() == null || request.retestReason().isBlank()) {
                throw new BizException("放行时必须记录" + (request.retestAdopt() ? "采用" : "拒绝") + "复测结果的理由");
            }
            for (SampleRetest round : pending) {
                round.setAdopted(request.retestAdopt());
                round.setAdoptReason(request.retestReason().trim());
                round.setReviewer(request.reviewer().trim());
                round.setReviewedAt(LocalDateTime.now());
            }
            retestRepository.saveAll(pending);
        }
        EffectiveResults effective = effectiveResults(sample, rounds);
        if (effective.alt() == null || effective.hb() == null || effective.hbv() == null
                || effective.hcv() == null || effective.hiv() == null || effective.syphilis() == null) {
            throw new BizException("标本 " + sample.getBarcode() + " 检验项目结果不全，不能放行");
        }
        String unqualified = unqualifiedReason(effective, sample.donorGender());
        if (unqualified != null) {
            throw new BizException("标本 " + sample.getBarcode() + " 检验不合格（" + unqualified + "），不能放行");
        }
        sample.release(request.reviewer().trim());
        return toVO(sampleRepository.save(sample), rounds);
    }

    /**
     * 轨道拖拽：待检 → 检测中 → 已放行 / 报废。
     */
    @Transactional
    public SampleVO move(Long id, String status, String reviewer) {
        TestSample sample = getSample(id);
        if (!BloodDict.isValidSampleStatus(status)) {
            throw new BizException("未知的标本状态：" + status);
        }
        switch (status) {
            case "PENDING" -> {
                if ("RELEASED".equals(sample.getStatus())) {
                    throw new BizException("已放行的标本不能退回待检");
                }
                sample.setStatus("PENDING");
            }
            case "TESTING" -> {
                if ("RELEASED".equals(sample.getStatus())) {
                    throw new BizException("已放行的标本不能退回检测中");
                }
                sample.setStatus("TESTING");
                if (sample.getTestedAt() == null) {
                    sample.setTestedAt(LocalDateTime.now());
                }
            }
            case "RELEASED" -> {
                return release(id, new ReleaseRequest(reviewer));
            }
            case "SCRAPPED" -> {
                if ("RELEASED".equals(sample.getStatus())) {
                    throw new BizException("已放行的标本不能作废，请走血液报废流程");
                }
                sample.scrap();
            }
            default -> throw new BizException("未知的标本状态：" + status);
        }
        return toVO(sampleRepository.save(sample), retestRepository.findBySampleIdOrderByIdAsc(sample.getId()));
    }

    // ---------------- 成分制备 ----------------

    @Transactional(readOnly = true)
    public List<PreparationVO> listPreparations(String bloodType) {
        List<PreparationBatch> batches = bloodType == null || bloodType.isBlank()
                ? preparationRepository.findAll()
                : preparationRepository.findByBloodTypeOrderByIdDesc(bloodType);
        List<PreparationVO> vos = new ArrayList<>();
        for (PreparationBatch batch : batches) {
            vos.add(toVO(batch));
        }
        return vos;
    }

    @Transactional(readOnly = true)
    public List<PreparationSummaryVO> preparationSummary() {
        return preparationRepository.summaryByBloodType();
    }

    // ---------------- 内部方法 ----------------

    /**
     * 按全血量计算可分离成分：红细胞悬液 / 血浆 / 血小板 / 冷沉淀。
     */
    public static int[] splitComponents(Integer wholeVolumeMl) {
        int volume = wholeVolumeMl == null ? 0 : wholeVolumeMl;
        int rbc = (int) Math.round(volume * 0.45);
        int plasma = (int) Math.round(volume * 0.40);
        int platelet = volume >= 350 ? 250 : 0;
        int cryo = volume >= 350 ? 30 : 0;
        return new int[]{rbc, plasma, platelet, cryo};
    }

    /**
     * 各项目当前有效结果：初检（test_sample 各列）+ 各项目最后一轮未被复核拒绝的复测。
     * 放行判定与溯源链展示共用同一口径。
     */
    public record EffectiveResults(String alt, String hb, String hbv, String hcv, String hiv, String syphilis) {
    }

    public static EffectiveResults effectiveResults(TestSample sample, List<SampleRetest> rounds) {
        String alt = sample.getAltValue() == null ? null : sample.getAltValue().toPlainString();
        String hb = sample.getHbValue() == null ? null : sample.getHbValue().toPlainString();
        String hbv = sample.getHbvResult();
        String hcv = sample.getHcvResult();
        String hiv = sample.getHivResult();
        String syphilis = sample.getSyphilisResult();
        Map<String, SampleRetest> latest = latestEffectiveRounds(rounds);
        for (Map.Entry<String, SampleRetest> entry : latest.entrySet()) {
            String value = entry.getValue().getResultValue();
            switch (entry.getKey()) {
                case "ALT" -> alt = value;
                case "HB" -> hb = value;
                case "HBV" -> hbv = value;
                case "HCV" -> hcv = value;
                case "HIV" -> hiv = value;
                case "SYPHILIS" -> syphilis = value;
                default -> {
                }
            }
        }
        return new EffectiveResults(alt, hb, hbv, hcv, hiv, syphilis);
    }

    /**
     * 各项目最后一轮「未被复核拒绝」的复测（待复核与已采用都算有效；被拒绝的轮次回退到上一轮）。
     */
    private static Map<String, SampleRetest> latestEffectiveRounds(List<SampleRetest> rounds) {
        Map<String, SampleRetest> latest = new LinkedHashMap<>();
        for (SampleRetest round : rounds) {
            if (Boolean.FALSE.equals(round.getAdopted())) {
                continue;
            }
            latest.put(round.getItemCode(), round);
        }
        return latest;
    }

    /**
     * 检验不合格原因（基于各项目当前有效结果）；合格返回 null。
     */
    private String unqualifiedReason(EffectiveResults results, String gender) {
        if (results.alt() != null && new BigDecimal(results.alt()).compareTo(BloodDict.ALT_LIMIT) > 0) {
            return "ALT " + results.alt() + " U/L 超过 " + BloodDict.ALT_LIMIT + " U/L";
        }
        if (results.hb() != null) {
            BigDecimal limit = "女".equals(gender) ? BloodDict.HB_LIMIT_FEMALE : BloodDict.HB_LIMIT_MALE;
            if (new BigDecimal(results.hb()).compareTo(limit) < 0) {
                return "血红蛋白 " + results.hb() + " g/L 低于 " + limit + " g/L";
            }
        }
        if ("POSITIVE".equals(results.hbv())) {
            return "HBV 阳性";
        }
        if ("POSITIVE".equals(results.hcv())) {
            return "HCV 阳性";
        }
        if ("POSITIVE".equals(results.hiv())) {
            return "HIV 阳性";
        }
        if ("POSITIVE".equals(results.syphilis())) {
            return "梅毒阳性";
        }
        return null;
    }

    /** 初检结果（test_sample 各列），未检返回 null */
    private String initialValue(TestSample sample, String itemCode) {
        return switch (itemCode) {
            case "ALT" -> sample.getAltValue() == null ? null : sample.getAltValue().toPlainString();
            case "HB" -> sample.getHbValue() == null ? null : sample.getHbValue().toPlainString();
            case "HBV" -> sample.getHbvResult();
            case "HCV" -> sample.getHcvResult();
            case "HIV" -> sample.getHivResult();
            case "SYPHILIS" -> sample.getSyphilisResult();
            default -> null;
        };
    }

    /** 复测新结果归一化：数值项目校验为合理数值，血清学项目只认 NEGATIVE/POSITIVE */
    private String normalizeResultValue(String itemCode, String raw) {
        if (raw == null || raw.isBlank()) {
            throw new BizException("复测必须填写新结果");
        }
        String value = raw.trim();
        if ("ALT".equals(itemCode) || "HB".equals(itemCode)) {
            BigDecimal number;
            try {
                number = new BigDecimal(value);
            } catch (NumberFormatException e) {
                throw new BizException("「" + BloodDict.testItemName(itemCode) + "」的复测结果必须是数值");
            }
            if (number.compareTo(BigDecimal.ZERO) <= 0 || number.compareTo(BigDecimal.valueOf(1000)) > 0) {
                throw new BizException("「" + BloodDict.testItemName(itemCode) + "」的复测结果数值超出合理范围");
            }
            return number.stripTrailingZeros().toPlainString();
        }
        String upper = value.toUpperCase();
        if (!"NEGATIVE".equals(upper) && !"POSITIVE".equals(upper)) {
            throw new BizException("「" + BloodDict.testItemName(itemCode) + "」的复测结果只能是 NEGATIVE（阴性）或 POSITIVE（阳性）");
        }
        return upper;
    }

    /** 单轮复测判定：QUALIFIED 合格 / UNQUALIFIED 不合格 */
    private String conclude(String itemCode, String resultValue, String gender) {
        switch (itemCode) {
            case "ALT" -> {
                return new BigDecimal(resultValue).compareTo(BloodDict.ALT_LIMIT) > 0 ? "UNQUALIFIED" : "QUALIFIED";
            }
            case "HB" -> {
                BigDecimal limit = "女".equals(gender) ? BloodDict.HB_LIMIT_FEMALE : BloodDict.HB_LIMIT_MALE;
                return new BigDecimal(resultValue).compareTo(limit) < 0 ? "UNQUALIFIED" : "QUALIFIED";
            }
            default -> {
                return "POSITIVE".equals(resultValue) ? "UNQUALIFIED" : "QUALIFIED";
            }
        }
    }

    private Map<Long, List<SampleRetest>> roundsBySample(List<TestSample> samples) {
        List<Long> ids = samples.stream().map(TestSample::getId).collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<SampleRetest>> grouped = new HashMap<>();
        for (SampleRetest round : retestRepository.findBySampleIdInOrderByIdAsc(ids)) {
            grouped.computeIfAbsent(round.getSample().getId(), k -> new ArrayList<>()).add(round);
        }
        return grouped;
    }

    private TestSample getSample(Long id) {
        return sampleRepository.findById(id).orElseThrow(() -> new BizException("标本不存在：id=" + id));
    }

    private SampleVO toVO(TestSample sample, List<SampleRetest> rounds) {
        CollectionRecord collection = sample.getCollection();
        String gender = sample.donorGender();
        EffectiveResults effective = effectiveResults(sample, rounds);
        Map<String, SampleRetest> currentRound = latestEffectiveRounds(rounds);
        BigDecimal hbLimit = "女".equals(gender) ? BloodDict.HB_LIMIT_FEMALE : BloodDict.HB_LIMIT_MALE;

        List<SampleVO.ItemVO> items = new ArrayList<>();
        items.add(new SampleVO.ItemVO("ALT", "ALT", str(effective.alt()), "U/L",
                effective.alt() == null ? "未检"
                        : (new BigDecimal(effective.alt()).compareTo(BloodDict.ALT_LIMIT) > 0 ? "偏高" : "合格"),
                sourceOf(currentRound.get("ALT"))));
        items.add(new SampleVO.ItemVO("HB", "血红蛋白", str(effective.hb()), "g/L",
                effective.hb() == null ? "未检"
                        : (new BigDecimal(effective.hb()).compareTo(hbLimit) < 0 ? "偏低" : "合格"),
                sourceOf(currentRound.get("HB"))));
        items.add(new SampleVO.ItemVO("HBV", "HBV", BloodDict.testResultName(effective.hbv()), "",
                effective.hbv() == null ? "未检" : BloodDict.testResultName(effective.hbv()),
                sourceOf(currentRound.get("HBV"))));
        items.add(new SampleVO.ItemVO("HCV", "HCV", BloodDict.testResultName(effective.hcv()), "",
                effective.hcv() == null ? "未检" : BloodDict.testResultName(effective.hcv()),
                sourceOf(currentRound.get("HCV"))));
        items.add(new SampleVO.ItemVO("HIV", "HIV", BloodDict.testResultName(effective.hiv()), "",
                effective.hiv() == null ? "未检" : BloodDict.testResultName(effective.hiv()),
                sourceOf(currentRound.get("HIV"))));
        items.add(new SampleVO.ItemVO("SYPHILIS", "梅毒", BloodDict.testResultName(effective.syphilis()), "",
                effective.syphilis() == null ? "未检" : BloodDict.testResultName(effective.syphilis()),
                sourceOf(currentRound.get("SYPHILIS"))));

        List<SampleVO.RetestRoundVO> retestVOs = new ArrayList<>();
        for (SampleRetest round : rounds) {
            SampleRetest current = currentRound.get(round.getItemCode());
            retestVOs.add(new SampleVO.RetestRoundVO(round.getId(), round.getItemCode(),
                    BloodDict.testItemName(round.getItemCode()), round.getRoundNo(),
                    displayValue(round.getItemCode(), round.getResultValue()), unitOf(round.getItemCode()),
                    round.getConclusion(), BloodDict.retestConclusionName(round.getConclusion()),
                    round.getReason(), round.getOperator(),
                    round.getAdopted(), round.getAdoptReason(), round.getReviewer(),
                    round.getReviewedAt(), round.getCreatedAt(),
                    current != null && current.getId().equals(round.getId())));
        }

        String currentVerdict;
        if (effective.alt() == null || effective.hb() == null || effective.hbv() == null
                || effective.hcv() == null || effective.hiv() == null || effective.syphilis() == null) {
            currentVerdict = "未检齐";
        } else {
            currentVerdict = unqualifiedReason(effective, gender) == null ? "合格" : "不合格";
        }

        return new SampleVO(sample.getId(), sample.getBarcode(),
                collection == null ? null : collection.getId(),
                collection == null ? null : collection.getCollectNo(),
                sample.getDonorName(), sample.getBloodType(), sample.getAltValue(), sample.getHbValue(),
                sample.getHbvResult(), sample.getHcvResult(), sample.getHivResult(), sample.getSyphilisResult(),
                sample.getReviewer(), sample.getStatus(), BloodDict.sampleStatusName(sample.getStatus()),
                currentVerdict,
                sample.getTestedAt(), sample.getReleasedAt(), items, retestVOs);
    }

    /** 当前有效结果的来源：初检 / 复测第 N 轮 */
    private String sourceOf(SampleRetest round) {
        return round == null ? "初检" : "复测第" + round.getRoundNo() + "轮";
    }

    private String displayValue(String itemCode, String resultValue) {
        if ("ALT".equals(itemCode) || "HB".equals(itemCode)) {
            return resultValue;
        }
        return BloodDict.testResultName(resultValue);
    }

    private String unitOf(String itemCode) {
        return switch (itemCode) {
            case "ALT" -> "U/L";
            case "HB" -> "g/L";
            default -> "";
        };
    }

    private String str(String value) {
        return value == null ? "--" : value;
    }

    private PreparationVO toVO(PreparationBatch batch) {
        int rbcBags = nz(batch.getRbcBags());
        int plasmaBags = nz(batch.getPlasmaBags());
        int plateletBags = nz(batch.getPlateletBags());
        int cryoBags = nz(batch.getCryoBags());
        int rbcVol = nz(batch.getRbcVolumeMl());
        int plasmaVol = nz(batch.getPlasmaVolumeMl());
        int plateletVol = nz(batch.getPlateletVolumeMl());
        int cryoVol = nz(batch.getCryoVolumeMl());
        return new PreparationVO(batch.getId(), batch.getBatchNo(),
                batch.getCollection() == null ? null : batch.getCollection().getId(),
                batch.getCollectNo(), batch.getBloodType(),
                rbcBags, rbcVol, plasmaBags, plasmaVol, plateletBags, plateletVol, cryoBags, cryoVol,
                batch.getOperator(), batch.getPreparedTime(), batch.getStatus(),
                rbcBags + plasmaBags + plateletBags + cryoBags,
                rbcVol + plasmaVol + plateletVol + cryoVol);
    }

    private int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
