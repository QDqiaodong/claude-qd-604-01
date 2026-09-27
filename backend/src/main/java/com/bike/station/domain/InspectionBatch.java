package com.bike.station.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 桩位巡检批次（模块六）。
 *
 * 建单时固化站点当时的桩位清单（逐桩落在 inspection_item）与占车情况快照，
 * 之后新增的桩位不会进入旧批次。
 *
 * 约定：业务字段不写 Java 默认值，默认值在 InspectionService 创建时赋值。
 */
@Entity
@Table(name = "inspection_batch")
public class InspectionBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_no", nullable = false, length = 32)
    private String batchNo;

    @Column(name = "station_id", nullable = false)
    private Long stationId;

    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "creator_id", nullable = false)
    private Long creatorId;

    @Column(name = "remark", length = 255)
    private String remark;

    @Column(name = "pile_count", nullable = false)
    private Integer pileCount;

    @Column(name = "snapshot_occupied_count", nullable = false)
    private Integer snapshotOccupiedCount;

    @Column(name = "snapshot_idle_count", nullable = false)
    private Integer snapshotIdleCount;

    @Column(name = "snapshot_disabled_count", nullable = false)
    private Integer snapshotDisabledCount;

    @Column(name = "checked_count", nullable = false)
    private Integer checkedCount;

    @Column(name = "normal_count", nullable = false)
    private Integer normalCount;

    @Column(name = "fault_count", nullable = false)
    private Integer faultCount;

    @Column(name = "pending_quarantine_count", nullable = false)
    private Integer pendingQuarantineCount;

    @Column(name = "quarantined_count", nullable = false)
    private Integer quarantinedCount;

    @Column(name = "pending_recheck_count", nullable = false)
    private Integer pendingRecheckCount;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getBatchNo() {
        return batchNo;
    }

    public void setBatchNo(String batchNo) {
        this.batchNo = batchNo;
    }

    public Long getStationId() {
        return stationId;
    }

    public void setStationId(Long stationId) {
        this.stationId = stationId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getCreatorId() {
        return creatorId;
    }

    public void setCreatorId(Long creatorId) {
        this.creatorId = creatorId;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public Integer getPileCount() {
        return pileCount;
    }

    public void setPileCount(Integer pileCount) {
        this.pileCount = pileCount;
    }

    public Integer getSnapshotOccupiedCount() {
        return snapshotOccupiedCount;
    }

    public void setSnapshotOccupiedCount(Integer snapshotOccupiedCount) {
        this.snapshotOccupiedCount = snapshotOccupiedCount;
    }

    public Integer getSnapshotIdleCount() {
        return snapshotIdleCount;
    }

    public void setSnapshotIdleCount(Integer snapshotIdleCount) {
        this.snapshotIdleCount = snapshotIdleCount;
    }

    public Integer getSnapshotDisabledCount() {
        return snapshotDisabledCount;
    }

    public void setSnapshotDisabledCount(Integer snapshotDisabledCount) {
        this.snapshotDisabledCount = snapshotDisabledCount;
    }

    public Integer getCheckedCount() {
        return checkedCount;
    }

    public void setCheckedCount(Integer checkedCount) {
        this.checkedCount = checkedCount;
    }

    public Integer getNormalCount() {
        return normalCount;
    }

    public void setNormalCount(Integer normalCount) {
        this.normalCount = normalCount;
    }

    public Integer getFaultCount() {
        return faultCount;
    }

    public void setFaultCount(Integer faultCount) {
        this.faultCount = faultCount;
    }

    public Integer getPendingQuarantineCount() {
        return pendingQuarantineCount;
    }

    public void setPendingQuarantineCount(Integer pendingQuarantineCount) {
        this.pendingQuarantineCount = pendingQuarantineCount;
    }

    public Integer getQuarantinedCount() {
        return quarantinedCount;
    }

    public void setQuarantinedCount(Integer quarantinedCount) {
        this.quarantinedCount = quarantinedCount;
    }

    public Integer getPendingRecheckCount() {
        return pendingRecheckCount;
    }

    public void setPendingRecheckCount(Integer pendingRecheckCount) {
        this.pendingRecheckCount = pendingRecheckCount;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
