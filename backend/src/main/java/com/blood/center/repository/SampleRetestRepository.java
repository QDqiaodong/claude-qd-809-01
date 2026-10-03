package com.blood.center.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.blood.center.domain.SampleRetest;

public interface SampleRetestRepository extends JpaRepository<SampleRetest, Long> {

    List<SampleRetest> findBySampleIdOrderByIdAsc(Long sampleId);

    List<SampleRetest> findBySampleIdInOrderByIdAsc(Collection<Long> sampleIds);

    long countBySampleId(Long sampleId);

    @Query("select coalesce(max(r.roundNo), 0) from SampleRetest r where r.sample.id = :sampleId and r.itemCode = :itemCode")
    int maxRoundNo(@Param("sampleId") Long sampleId, @Param("itemCode") String itemCode);
}
