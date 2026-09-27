package com.bike.station;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import com.bike.station.common.BizException;
import com.bike.station.domain.BikeDict;
import com.bike.station.domain.InspectionDict;
import com.bike.station.domain.Pile;
import com.bike.station.dto.InspectionDtos;
import com.bike.station.dto.RentalDtos;
import com.bike.station.dto.StationDtos;
import com.bike.station.dto.Views;
import com.bike.station.repository.BikeRepository;
import com.bike.station.repository.PileRepository;
import com.bike.station.service.BikeService;
import com.bike.station.service.InspectionService;
import com.bike.station.service.RentalService;
import com.bike.station.service.StationService;

/**
 * 桩位巡检（模块六）端到端：用 H2（MySQL 模式）跑真实的 Spring/JPA/事务/行锁流程。
 * 每个用例前用 reset-h2.sql 重置种子。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/reset-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class InspectionFlowTests {

    @Autowired
    private InspectionService inspectionService;
    @Autowired
    private RentalService rentalService;
    @Autowired
    private StationService stationService;
    @Autowired
    private PileRepository pileRepository;
    @Autowired
    private BikeRepository bikeRepository;
    @Autowired
    private BikeService bikeService;

    private static final Long OPERATOR_1 = 1L;
    private static final Long OPERATOR_3_OFF = 3L;

    private Views.InspectionBatchView createBatch(long stationId) {
        return inspectionService.create(new InspectionDtos.BatchCreate(stationId, OPERATOR_1, null));
    }

    private Views.InspectionItemView itemOf(Views.InspectionBatchView batch, long pileId) {
        return batch.items().stream()
                .filter(i -> i.pileId() == pileId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("批次中缺少桩位 " + pileId));
    }

    private InspectionDtos.CheckRequest check(boolean lockFault, String level) {
        return new InspectionDtos.CheckRequest(
                OPERATOR_1,
                lockFault ? InspectionDict.CHECK_FAIL : InspectionDict.CHECK_OK,
                InspectionDict.CHECK_OK,
                InspectionDict.CHECK_OK,
                lockFault ? level : null,
                lockFault ? "桩锁无法弹开" : null,
                null,
                InspectionDict.RESULT_WAITING);
    }

    private void checkPile(long batchId, Views.InspectionItemView item, boolean fault) {
        inspectionService.check(batchId, item.id(),
                new InspectionDtos.CheckRequest(
                        OPERATOR_1,
                        fault ? InspectionDict.CHECK_FAIL : InspectionDict.CHECK_OK,
                        InspectionDict.CHECK_OK,
                        InspectionDict.CHECK_OK,
                        fault ? "HIGH" : null,
                        fault ? "桩锁无法弹开" : null,
                        null,
                        item.itemResult()));
    }

    // ------------------------------------------------------------------
    // 1. 建单固定桩位清单与占车快照；之后新增桩位不进旧批次
    // ------------------------------------------------------------------
    @Test
    void batchFreezesPileListAndSnapshot() {
        Views.InspectionBatchView batch = createBatch(1L);
        // S001 初始 7 个桩位（P1~P7）
        assertEquals(7, batch.pileCount());
        assertEquals(4, batch.snapshotOccupiedCount()); // P1/P2/P3/P7
        assertEquals(2, batch.snapshotIdleCount());     // P4/P5
        assertEquals(1, batch.snapshotDisabledCount()); // P6

        // 旧批次之后新增桩位不应混入
        stationService.addPile(1L, new StationDtos.PileCreate("P8"));
        Views.InspectionBatchView reloaded = inspectionService.detail(batch.id());
        assertEquals(7, reloaded.pileCount());
        assertEquals(7, reloaded.items().size());
        assertTrue(reloaded.items().stream().noneMatch(i -> "P8".equals(i.pileNo())));

        // 同站可以再建一个历史批次，新批次包含 P8
        Views.InspectionBatchView second = createBatch(1L);
        assertEquals(8, second.pileCount());
        assertTrue(second.items().stream().anyMatch(i -> "P8".equals(i.pileNo())));
    }

    // ------------------------------------------------------------------
    // 2. 空桩故障立即隔离：不出现在还车/调拨可选桩位
    // ------------------------------------------------------------------
    @Test
    void emptyPileFaultQuarantinesImmediatelyAndBlocksRentalsAndTransfers() {
        long batchId = createBatch(1L).id();
        Views.InspectionBatchView batch = inspectionService.detail(batchId);
        Views.InspectionItemView idlePile = itemOf(batch, 104L); // P4 空桩

        inspectionService.check(batchId, idlePile.id(), check(true, "HIGH"));

        Pile pile = pileRepository.findById(104L).orElseThrow();
        assertEquals(BikeDict.PILE_QUARANTINED, pile.getStatus());
        assertTrue(bikeRepository.findByPileId(104L).isEmpty());

        // 还车到已隔离桩必须被拒（骑行中订单 5：BK-2001）
        BizException ex = assertThrows(BizException.class,
                () -> rentalService.returnBike(5L, new RentalDtos.Return(104L)));
        assertTrue(ex.getMessage().contains("已隔离"), ex.getMessage());

        // 调拨到已隔离桩必须被拒（BK-1004 从 P107 调入 P104）
        BizException tex = assertThrows(BizException.class, () -> stationService.transfer(
                new StationDtos.Transfer(1004L, 104L, null)));
        assertTrue(tex.getMessage().contains("已隔离"), tex.getMessage());

        // 看板上能直接看到已隔离数量
        Views.StationCard card = stationService.cards(null).stream()
                .filter(c -> c.id() == 1L).findFirst().orElseThrow();
        assertEquals(1, card.quarantinedCount());
        assertEquals(0, card.pendingQuarantineCount());
        // 已隔离空桩不计入可用空桩
        long freeP104 = card.piles().stream().filter(p -> p.id() == 104L)
                .filter(p -> "IDLE".equals(p.status())).count();
        assertEquals(0, freeP104);
    }

    // ------------------------------------------------------------------
    // 3. 有车故障进待隔离：车可借走/调出，新车进不来；车离开自动转已隔离
    // ------------------------------------------------------------------
    @Test
    void occupiedPileFaultPendingQuarantineThenAutoIsolatedAfterBikeLeaves() {
        long batchId = createBatch(1L).id();
        Views.InspectionItemView occupied = itemOf(inspectionService.detail(batchId), 101L); // P1 / BK-1001

        inspectionService.check(batchId, occupied.id(), check(true, "MID"));
        Pile pile = pileRepository.findById(101L).orElseThrow();
        assertEquals(BikeDict.PILE_PENDING_QUARANTINE, pile.getStatus());
        // 车辆落点不能被清掉
        assertEquals(1001L, bikeRepository.findByPileId(101L).orElseThrow().getId());

        // 待隔离桩上的车仍可被正常借走（借车人 1 余额充足）
        rentalService.borrow(new RentalDtos.Borrow(1L, 101L));
        assertEquals(BikeDict.PILE_QUARANTINED, pileRepository.findById(101L).orElseThrow().getStatus());
        assertNull(bikeRepository.findByPileId(101L).orElse(null));

        // 另一辆在桩车（BK-1004 / P107）调进来必须被拒：待隔离/已隔离桩不接收新车
        BizException ex = assertThrows(BizException.class, () -> stationService.transfer(
                new StationDtos.Transfer(1004L, 101L, null)));
        assertTrue(ex.getMessage().contains("隔离"), ex.getMessage());

        // 运营人员可以把待隔离桩上的车调出（重新造一个待隔离：P2/BK-1002）
        Views.InspectionItemView p2 = itemOf(inspectionService.detail(batchId), 102L);
        inspectionService.check(batchId, p2.id(), check(true, "LOW"));
        assertEquals(BikeDict.PILE_PENDING_QUARANTINE, pileRepository.findById(102L).orElseThrow().getStatus());
        Views.TransferResult tr = stationService.transfer(
                new StationDtos.Transfer(1002L, 109L, null)); // 调到 S002/P3
        assertNotNull(tr);
        assertEquals(BikeDict.PILE_QUARANTINED, pileRepository.findById(102L).orElseThrow().getStatus());
    }

    // ------------------------------------------------------------------
    // 4. 检查期间占用被别的业务动作改变：按最新事实落地，不留矛盾状态
    // ------------------------------------------------------------------
    @Test
    void checkAfterBikeLeftDuringInspectionQuarantinesInsteadOfPending() {
        long batchId = createBatch(1L).id();
        Views.InspectionItemView p3 = itemOf(inspectionService.detail(batchId), 103L); // P3 / BK-1003

        // 页面打开时是占用（expectedBikeCode=BK-1003），登记前车刚好被借走
        rentalService.borrow(new RentalDtos.Borrow(1L, 103L));

        InspectionDtos.CheckRequest stalePage = new InspectionDtos.CheckRequest(
                OPERATOR_1, "FAULT_FAIL", "NORMAL_OK", "NORMAL_OK", "HIGH", "桩锁卡死", "BK-1003",
                InspectionDict.RESULT_WAITING);
        inspectionService.check(batchId, p3.id(), stalePage);

        // 最新事实是空桩 → 直接已隔离，而不是待隔离；且检查记录里保留了占用变化说明
        assertEquals(BikeDict.PILE_QUARANTINED, pileRepository.findById(103L).orElseThrow().getStatus());
        Views.InspectionBatchView after = inspectionService.detail(batchId);
        Views.InspectionItemView item = itemOf(after, 103L);
        assertNotNull(item.inspectOccupyNote());
        assertTrue(item.inspectOccupyNote().contains("已按最新事实决定"));
        assertNull(item.currentBikeCode());
    }

    // ------------------------------------------------------------------
    // 5. 同桩不能同时有两条未完成故障处置（跨批次也拦）；必须复验通过才能再登记
    // ------------------------------------------------------------------
    @Test
    void samePileCannotHaveTwoOpenDisposals() {
        long firstId = createBatch(1L).id();
        Views.InspectionItemView p4 = itemOf(inspectionService.detail(firstId), 104L);
        inspectionService.check(firstId, p4.id(), check(true, "HIGH"));

        // 同站再建一个批次，对同一桩登记故障 → 拒绝
        long secondId = createBatch(1L).id();
        Views.InspectionItemView p4Again = itemOf(inspectionService.detail(secondId), 104L);
        BizException ex = assertThrows(BizException.class,
                () -> inspectionService.check(secondId, p4Again.id(), check(true, "MID")));
        assertTrue(ex.getMessage().contains("尚未完成的故障处置"), ex.getMessage());

        // 第一条走完 处置完成 → 复验通过 后，第二条批次才能再登记
        inspectionService.finishDispose(firstId, p4.id(),
                new InspectionDtos.DisposeFinishRequest(OPERATOR_1, "更换桩锁"));
        Views.RecheckOutcome ok = inspectionService.recheck(firstId, p4.id(),
                new InspectionDtos.RecheckRequest(OPERATOR_1, "PASS", null));
        assertTrue(ok.passed());
        assertEquals(BikeDict.PILE_IDLE, ok.pileStatus());

        inspectionService.check(secondId, p4Again.id(), check(true, "MID"));
        assertEquals(BikeDict.PILE_QUARANTINED, pileRepository.findById(104L).orElseThrow().getStatus());
    }

    // ------------------------------------------------------------------
    // 6. 处置 → 复验：不通过保留原因继续隔离；通过按真实占车恢复，不能手工指定
    // ------------------------------------------------------------------
    @Test
    void disposeAndRecheckFailKeepsIsolationPassRestoresByTruth() {
        long batchId = createBatch(1L).id();
        Views.InspectionItemView idle = itemOf(inspectionService.detail(batchId), 104L);
        inspectionService.check(batchId, idle.id(), check(true, "HIGH"));

        // 未完成处置不能复验
        BizException early = assertThrows(BizException.class,
                () -> inspectionService.recheck(batchId, idle.id(),
                        new InspectionDtos.RecheckRequest(OPERATOR_1, "PASS", null)));
        assertTrue(early.getMessage().contains("尚未完成"), early.getMessage());

        inspectionService.finishDispose(batchId, idle.id(),
                new InspectionDtos.DisposeFinishRequest(OPERATOR_1, "更换锁舌"));
        // 处置完成仍是已隔离，且进入待复验
        assertEquals(BikeDict.PILE_QUARANTINED, pileRepository.findById(104L).orElseThrow().getStatus());

        // 复验不通过必须填原因，桩位继续隔离，处置回到处置中
        BizException noReason = assertThrows(BizException.class,
                () -> inspectionService.recheck(batchId, idle.id(),
                        new InspectionDtos.RecheckRequest(OPERATOR_1, "FAIL", "  ")));
        assertTrue(noReason.getMessage().contains("不通过原因"));

        Views.RecheckOutcome fail = inspectionService.recheck(batchId, idle.id(),
                new InspectionDtos.RecheckRequest(OPERATOR_1, "FAIL", "锁舌仍卡顿"));
        assertFalse(fail.passed());
        assertEquals(BikeDict.PILE_QUARANTINED, pileRepository.findById(104L).orElseThrow().getStatus());
        Views.InspectionItemView afterFail = itemOf(inspectionService.detail(batchId), 104L);
        assertEquals("PROCESSING", afterFail.disposeStatus());
        assertEquals(1, afterFail.recheckFailCount());
        assertEquals("锁舌仍卡顿", afterFail.recheckNote());

        // 重新处置完成再复验通过：空桩恢复为 IDLE
        inspectionService.finishDispose(batchId, idle.id(),
                new InspectionDtos.DisposeFinishRequest(OPERATOR_1, "再次更换锁舌并调试"));
        Views.RecheckOutcome pass = inspectionService.recheck(batchId, idle.id(),
                new InspectionDtos.RecheckRequest(OPERATOR_1, "PASS", "复测三次正常"));
        assertTrue(pass.passed());
        assertEquals(BikeDict.PILE_IDLE, pileRepository.findById(104L).orElseThrow().getStatus());

        Views.InspectionItemView done = itemOf(inspectionService.detail(batchId), 104L);
        assertEquals("RECHECK_PASS", done.disposeStatus());
        // 复验记录里保留了两条（一次不通过 + 一次通过），不通过原因可追溯
        assertEquals(2, done.verifyLogs().size());
        assertEquals("FAIL", done.verifyLogs().get(0).result());
        assertEquals("锁舌仍卡顿", done.verifyLogs().get(0).note());
        assertEquals("PASS", done.verifyLogs().get(1).result());
        assertEquals("空闲", done.verifyLogs().get(1).pileRestoredName());
    }

    // ------------------------------------------------------------------
    // 7. 复验通过时桩上有车 → 恢复为占用（按真实占车，页面无法指定）
    // ------------------------------------------------------------------
    @Test
    void recheckPassOnPendingQuarantineAfterDisposeRestoresOccupied() {
        long batchId = createBatch(1L).id();
        Views.InspectionItemView p1 = itemOf(inspectionService.detail(batchId), 101L); // BK-1001
        inspectionService.check(batchId, p1.id(), check(true, "MID"));
        assertEquals(BikeDict.PILE_PENDING_QUARANTINE, pileRepository.findById(101L).orElseThrow().getStatus());

        inspectionService.finishDispose(batchId, p1.id(),
                new InspectionDtos.DisposeFinishRequest(OPERATOR_1, "现场修复通信模块"));
        // 车始终没离开 → 复验通过必须恢复为 OCCUPIED
        Views.RecheckOutcome pass = inspectionService.recheck(batchId, p1.id(),
                new InspectionDtos.RecheckRequest(OPERATOR_1, "PASS", null));
        assertTrue(pass.passed());
        assertEquals(BikeDict.PILE_OCCUPIED, pileRepository.findById(101L).orElseThrow().getStatus());
        assertEquals("BK-1001", pass.currentBikeCode());
        assertEquals(1001L, bikeRepository.findByPileId(101L).orElseThrow().getId());
    }

    // ------------------------------------------------------------------
    // 8. 并发：两名运营同时登记同一桩，只有一方结果生效，另一方看到最新状态
    // ------------------------------------------------------------------
    @Test
    void concurrentCheckOnSamePileOneWinsNoOverwrite() throws Exception {
        long batchId = createBatch(1L).id();
        long itemId = itemOf(inspectionService.detail(batchId), 104L).id();

        int threads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Throwable> errors = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    inspectionService.check(batchId, itemId, check(true, "HIGH"));
                    ok.incrementAndGet();
                } catch (BizException e) {
                    rejected.incrementAndGet();
                } catch (Throwable e) {
                    errors.add(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS), "并发登记未在时限内结束");
        assertTrue(errors.isEmpty(), "并发登记出现非业务异常：" + errors);

        // 两个旧页面都带着「待检查」提交：一方先生效，另一方必须收到明确冲突（而不是静默覆盖）
        assertEquals(1, ok.get(), "应当恰好有一方登记成功");
        assertEquals(1, rejected.get(), "后一方应当看到冲突");
        assertEquals(BikeDict.PILE_QUARANTINED, pileRepository.findById(104L).orElseThrow().getStatus());
        Views.InspectionItemView item = itemOf(inspectionService.detail(batchId), 104L);
        assertEquals("FAULT", item.itemResult());
        assertEquals("PROCESSING", item.disposeStatus());

        // 后一方刷新看到最新结论后重新登记（previousResult=FAULT）→ 允许，按最新事实覆盖为正常
        inspectionService.check(batchId, item.id(),
                new InspectionDtos.CheckRequest(OPERATOR_1, InspectionDict.CHECK_OK,
                        InspectionDict.CHECK_OK, InspectionDict.CHECK_OK, null, null, null,
                        InspectionDict.RESULT_FAULT));
        // 正常登记不会解除隔离（空桩仍已隔离，安全隔离优先）
        assertEquals(BikeDict.PILE_QUARANTINED, pileRepository.findById(104L).orElseThrow().getStatus());
    }

    // ------------------------------------------------------------------
    // 9. 并发：车辆离桩（借车）与复验通过相撞，复验按最新事实恢复为空闲
    // ------------------------------------------------------------------
    @Test
    void recheckRacingWithBorrowRestoresByLatestTruth() {
        long batchId = createBatch(1L).id();
        long itemId = itemOf(inspectionService.detail(batchId), 101L).id(); // BK-1001
        inspectionService.check(batchId, itemId, check(true, "MID"));
        inspectionService.finishDispose(batchId, itemId,
                new InspectionDtos.DisposeFinishRequest(OPERATOR_1, "已修复"));
        // 此刻桩是 PENDING_QUARANTINE（有车）。先把车借走，再复验 → 必须恢复为 IDLE
        rentalService.borrow(new RentalDtos.Borrow(1L, 101L));
        Views.RecheckOutcome pass = inspectionService.recheck(batchId, itemId,
                new InspectionDtos.RecheckRequest(OPERATOR_1, "PASS", null));
        assertTrue(pass.passed());
        assertEquals(BikeDict.PILE_IDLE, pass.pileStatus());
        assertNull(pass.currentBikeCode());
        assertEquals(BikeDict.PILE_IDLE, pileRepository.findById(101L).orElseThrow().getStatus());
    }

    // ------------------------------------------------------------------
    // 10. 全部桩检查完且无未完成处置时批次自动完成；有未完成处置则保持巡检中
    // ------------------------------------------------------------------
    @Test
    void batchAutoCompletesOnlyWhenAllCheckedAndNoOpenDisposal() {
        long batchId = createBatch(2L).id(); // S002：P1/P2 占用，P3/P4 空闲
        List<Views.InspectionItemView> items = inspectionService.detail(batchId).items();
        assertEquals(4, items.size());

        // 前三桩正常
        for (int i = 0; i < 3; i++) {
            checkPile(batchId, items.get(i), false);
        }
        assertEquals("OPEN", inspectionService.detail(batchId).status());

        // 最后一桩报故障（空桩 110 立即隔离）
        checkPile(batchId, items.get(3), true);
        assertEquals("OPEN", inspectionService.detail(batchId).status());

        // 走完处置与复验 → 批次完成
        long lastItemId = items.get(3).id();
        inspectionService.finishDispose(batchId, lastItemId,
                new InspectionDtos.DisposeFinishRequest(OPERATOR_1, "修复"));
        inspectionService.recheck(batchId, lastItemId,
                new InspectionDtos.RecheckRequest(OPERATOR_1, "PASS", null));
        Views.InspectionBatchView done = inspectionService.detail(batchId);
        assertEquals("COMPLETED", done.status());
        assertNotNull(done.finishedAt());
        assertEquals(4, done.checkedCount());
    }

    // ------------------------------------------------------------------
    // 11. 休息中的运营人员不能登记/处置/复验
    // ------------------------------------------------------------------
    @Test
    void offDutyOperatorRejected() {
        long batchId = createBatch(1L).id();
        long itemId = itemOf(inspectionService.detail(batchId), 104L).id();
        BizException ex = assertThrows(BizException.class,
                () -> inspectionService.check(batchId, itemId,
                        new InspectionDtos.CheckRequest(OPERATOR_3_OFF,
                                "FAULT_FAIL", "NORMAL_OK", "NORMAL_OK", "HIGH", "故障", null,
                                InspectionDict.RESULT_WAITING)));
        assertTrue(ex.getMessage().contains("休息中"), ex.getMessage());
    }

    // ------------------------------------------------------------------
    // 12. 待隔离桩上的车被运营人员送修（人工改状态）后，桩位自动转已隔离
    // ------------------------------------------------------------------
    @Test
    void bikeSentToRepairFromPendingPileAutoIsolates() {
        long batchId = createBatch(1L).id();
        Views.InspectionItemView p2 = itemOf(inspectionService.detail(batchId), 102L); // BK-1002
        inspectionService.check(batchId, p2.id(), check(true, "MID"));
        assertEquals(BikeDict.PILE_PENDING_QUARANTINE, pileRepository.findById(102L).orElseThrow().getStatus());

        // 通过车辆台账人工置为维修中（车离桩但留在站点维修区）
        bikeService.update(1002L, new com.bike.station.dto.BikeDtos.BikeUpdate(
                null, BikeDict.BIKE_REPAIR, null, null, null));
        assertEquals(BikeDict.PILE_QUARANTINED, pileRepository.findById(102L).orElseThrow().getStatus());
        assertNull(bikeRepository.findById(1002L).orElseThrow().getPileId());
    }

    // ------------------------------------------------------------------
    // 13. 复验通过恢复空闲后，桩位重新出现在还车可选范围，车能正常还进来
    // ------------------------------------------------------------------
    @Test
    void restoredIdlePileAcceptsReturnAgain() {
        long batchId = createBatch(1L).id();
        long itemId = itemOf(inspectionService.detail(batchId), 104L).id(); // P4 空桩
        inspectionService.check(batchId, itemId, check(true, "HIGH"));
        inspectionService.finishDispose(batchId, itemId,
                new InspectionDtos.DisposeFinishRequest(OPERATOR_1, "修复"));
        inspectionService.recheck(batchId, itemId,
                new InspectionDtos.RecheckRequest(OPERATOR_1, "PASS", null));
        assertEquals(BikeDict.PILE_IDLE, pileRepository.findById(104L).orElseThrow().getStatus());

        // 骑行中的 BK-2001（在途单 5）还到 P4 应当成功，桩位重新变占用
        rentalService.returnBike(5L, new RentalDtos.Return(104L));
        assertEquals(BikeDict.PILE_OCCUPIED, pileRepository.findById(104L).orElseThrow().getStatus());
        assertEquals(2001L, bikeRepository.findByPileId(104L).orElseThrow().getId());
    }
}
