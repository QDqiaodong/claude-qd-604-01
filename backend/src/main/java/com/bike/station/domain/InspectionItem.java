package com.bike.station.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 巡检批次桩位明细：一桩一行，建单时随批次固化。
 *
 * item_result 是检查结论（待检查/正常/故障）；dispose_status 是故障处置状态机。
 * active_pile_id 是数据库生成列（处置未复验通过时等于 pile_id），带唯一索引，
 * 在数据库层面硬保证「同一桩位不能同时存在两条尚未完成的故障处置」——
 * JPA 侧只读不写。
 *
 * 约定：业务字段不写 Java 默认值，默认值在 InspectionService 创建时赋值。
 */
@Entity
@Table(name = "inspection_item")
public class InspectionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "batch_id", nullable = false)
    private Long batchId;

    @Column(name = "pile_id", nullable = false)
    private Long pileId;

    @Column(name = "pile_no", nullable = false, length = 16)
    private String pileNo;

    @Column(name = "snapshot_bike_code", length = 32)
    private String snapshotBikeCode;

    @Column(name = "item_result", nullable = false, length = 16)
    private String itemResult;

    @Column(name = "lock_result", length = 16)
    private String lockResult;

    @Column(name = "power_result", length = 16)
    private String powerResult;

    @Column(name = "com_result", length = 16)
    private String comResult;

    @Column(name = "fault_level", length = 16)
    private String faultLevel;

    @Column(name = "fault_desc", length = 255)
    private String faultDesc;

    @Column(name = "dispose_status", nullable = false, length = 16)
    private String disposeStatus;

    @Column(name = "inspector_id")
    private Long inspectorId;

    @Column(name = "inspected_at")
    private LocalDateTime inspectedAt;

    @Column(name = "inspect_occupy_note", length = 255)
    private String inspectOccupyNote;

    @Column(name = "disposer_id")
    private Long disposerId;

    @Column(name = "dispose_started_at")
    private LocalDateTime disposeStartedAt;

    @Column(name = "dispose_note", length = 255)
    private String disposeNote;

    @Column(name = "disposed_at")
    private LocalDateTime disposedAt;

    @Column(name = "rechecker_id")
    private Long recheckerId;

    @Column(name = "rechecked_at")
    private LocalDateTime recheckedAt;

    @Column(name = "recheck_result", length = 16)
    private String recheckResult;

    @Column(name = "recheck_note", length = 255)
    private String recheckNote;

    @Column(name = "recheck_pass_count", nullable = false)
    private Integer recheckPassCount;

    @Column(name = "recheck_fail_count", nullable = false)
    private Integer recheckFailCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** 数据库生成列：处置未复验通过时等于 pileId；JPA 只读，绝不写入 */
    @Column(name = "active_pile_id", insertable = false, updatable = false)
    private Long activePileId;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getBatchId() {
        return batchId;
    }

    public void setBatchId(Long batchId) {
        this.batchId = batchId;
    }

    public Long getPileId() {
        return pileId;
    }

    public void setPileId(Long pileId) {
        this.pileId = pileId;
    }

    public String getPileNo() {
        return pileNo;
    }

    public void setPileNo(String pileNo) {
        this.pileNo = pileNo;
    }

    public String getSnapshotBikeCode() {
        return snapshotBikeCode;
    }

    public void setSnapshotBikeCode(String snapshotBikeCode) {
        this.snapshotBikeCode = snapshotBikeCode;
    }

    public String getItemResult() {
        return itemResult;
    }

    public void setItemResult(String itemResult) {
        this.itemResult = itemResult;
    }

    public String getLockResult() {
        return lockResult;
    }

    public void setLockResult(String lockResult) {
        this.lockResult = lockResult;
    }

    public String getPowerResult() {
        return powerResult;
    }

    public void setPowerResult(String powerResult) {
        this.powerResult = powerResult;
    }

    public String getComResult() {
        return comResult;
    }

    public void setComResult(String comResult) {
        this.comResult = comResult;
    }

    public String getFaultLevel() {
        return faultLevel;
    }

    public void setFaultLevel(String faultLevel) {
        this.faultLevel = faultLevel;
    }

    public String getFaultDesc() {
        return faultDesc;
    }

    public void setFaultDesc(String faultDesc) {
        this.faultDesc = faultDesc;
    }

    public String getDisposeStatus() {
        return disposeStatus;
    }

    public void setDisposeStatus(String disposeStatus) {
        this.disposeStatus = disposeStatus;
    }

    public Long getInspectorId() {
        return inspectorId;
    }

    public void setInspectorId(Long inspectorId) {
        this.inspectorId = inspectorId;
    }

    public LocalDateTime getInspectedAt() {
        return inspectedAt;
    }

    public void setInspectedAt(LocalDateTime inspectedAt) {
        this.inspectedAt = inspectedAt;
    }

    public String getInspectOccupyNote() {
        return inspectOccupyNote;
    }

    public void setInspectOccupyNote(String inspectOccupyNote) {
        this.inspectOccupyNote = inspectOccupyNote;
    }

    public Long getDisposerId() {
        return disposerId;
    }

    public void setDisposerId(Long disposerId) {
        this.disposerId = disposerId;
    }

    public LocalDateTime getDisposeStartedAt() {
        return disposeStartedAt;
    }

    public void setDisposeStartedAt(LocalDateTime disposeStartedAt) {
        this.disposeStartedAt = disposeStartedAt;
    }

    public String getDisposeNote() {
        return disposeNote;
    }

    public void setDisposeNote(String disposeNote) {
        this.disposeNote = disposeNote;
    }

    public LocalDateTime getDisposedAt() {
        return disposedAt;
    }

    public void setDisposedAt(LocalDateTime disposedAt) {
        this.disposedAt = disposedAt;
    }

    public Long getRecheckerId() {
        return recheckerId;
    }

    public void setRecheckerId(Long recheckerId) {
        this.recheckerId = recheckerId;
    }

    public LocalDateTime getRecheckedAt() {
        return recheckedAt;
    }

    public void setRecheckedAt(LocalDateTime recheckedAt) {
        this.recheckedAt = recheckedAt;
    }

    public String getRecheckResult() {
        return recheckResult;
    }

    public void setRecheckResult(String recheckResult) {
        this.recheckResult = recheckResult;
    }

    public String getRecheckNote() {
        return recheckNote;
    }

    public void setRecheckNote(String recheckNote) {
        this.recheckNote = recheckNote;
    }

    public Integer getRecheckPassCount() {
        return recheckPassCount;
    }

    public void setRecheckPassCount(Integer recheckPassCount) {
        this.recheckPassCount = recheckPassCount;
    }

    public Integer getRecheckFailCount() {
        return recheckFailCount;
    }

    public void setRecheckFailCount(Integer recheckFailCount) {
        this.recheckFailCount = recheckFailCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Long getActivePileId() {
        return activePileId;
    }

    public void setActivePileId(Long activePileId) {
        // 生成列由数据库维护，显式置值只接受 null，避免误写
        this.activePileId = activePileId;
    }
}
