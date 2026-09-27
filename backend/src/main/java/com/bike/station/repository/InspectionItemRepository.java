package com.bike.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.InspectionItem;

import jakarta.persistence.LockModeType;

public interface InspectionItemRepository extends JpaRepository<InspectionItem, Long> {

    List<InspectionItem> findByBatchIdOrderByIdAsc(Long batchId);

    Optional<InspectionItem> findByBatchIdAndPileId(Long batchId, Long pileId);

    long countByBatchIdAndResult(Long batchId, String result);

    long countByBatchIdAndResultIn(Long batchId, List<String> results);

    /**
     * 【悲观锁】锁逐桩明细行：乐观版本 rev 的比对与更新在行锁内进行，
     * 两个并发登记请求在这里串行，旧 rev 的一方拿到 409 冲突。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InspectionItem i where i.id = :id")
    Optional<InspectionItem> findByIdForUpdate(@Param("id") Long id);
}
