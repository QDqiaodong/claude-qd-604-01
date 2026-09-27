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

    Optional<InspectionBatch> findByBatchNo(String batchNo);

    List<InspectionBatch> findAllByOrderByIdDesc();

    List<InspectionBatch> findByStationIdOrderByIdDesc(Long stationId);

    List<InspectionBatch> findByStatusOrderByIdDesc(String status);

    long countByStatus(String status);

    /**
     * 【悲观锁】锁巡检批次行：登记逐桩结果、推进批次进度都先锁它，
     * 两名运营人员同时登记时在批次计数上串行，checked/fault 计数不会丢更新。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from InspectionBatch b where b.id = :id")
    Optional<InspectionBatch> findByIdForUpdate(@Param("id") Long id);
}
