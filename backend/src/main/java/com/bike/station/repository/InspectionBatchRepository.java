package com.bike.station.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bike.station.domain.InspectionBatch;

import jakarta.persistence.LockModeType;

public interface InspectionBatchRepository extends JpaRepository<InspectionBatch, Long> {

    List<InspectionBatch> findAllByOrderByIdDesc();

    List<InspectionBatch> findByStationIdOrderByIdDesc(Long stationId);

    List<InspectionBatch> findByStatusOrderByIdDesc(String status);

    long countByStatus(String status);

    /**
     * 【悲观锁】按主键锁 inspection_batch 表的这一行：
     * 逐桩检查、处置、复验改批次计数时都先锁它，两个并发动作不会互相覆盖计数与状态。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from InspectionBatch b where b.id = :id")
    Optional<InspectionBatch> findByIdForUpdate(@Param("id") Long id);
}
