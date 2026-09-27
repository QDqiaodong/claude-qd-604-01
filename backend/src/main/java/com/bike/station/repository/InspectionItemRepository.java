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

    long countByBatchIdAndItemResult(Long batchId, String itemResult);

    long countByBatchIdAndDisposeStatus(Long batchId, String disposeStatus);

    /** 站点看板：某站点下处于指定处置状态的明细数（如全部批次里的待复验数） */
    @Query("select count(i) from InspectionItem i join InspectionBatch b on i.batchId = b.id"
            + " where b.stationId = :stationId and i.disposeStatus = :status")
    long countByStationIdAndDisposeStatus(@Param("stationId") Long stationId,
                                          @Param("status") String status);

    /** 顶部概览：全部批次里处于指定处置状态的明细数 */
    long countByDisposeStatus(String status);

    /**
     * 某桩位是否已存在一条「尚未完成」（未复验通过）的故障处置。
     * 服务层用它提前失败；数据库层还有 active_pile_id 唯一索引兜底并发。
     */
    @Query("select i from InspectionItem i where i.pileId = :pileId"
            + " and i.disposeStatus in ('PROCESSING', 'FIXED', 'RECHECK_FAIL')")
    List<InspectionItem> findOpenDisposeByPileId(@Param("pileId") Long pileId);

    /**
     * 【悲观锁】锁住一条批次桩位明细：
     * 两名运营人员几乎同时登记同一桩位的检查/复验时，后拿到锁的一方会看到前一方的最新结果，
     * 旧页面不能覆盖已生效的检查记录与车辆落点。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InspectionItem i where i.id = :id")
    Optional<InspectionItem> findByIdForUpdate(@Param("id") Long id);

    /** 锁整批明细（批次完成判定用，保证计数读到的是锁内最新值） */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from InspectionItem i where i.batchId = :batchId order by i.id asc")
    List<InspectionItem> findByBatchIdForUpdate(@Param("batchId") Long batchId);
}
