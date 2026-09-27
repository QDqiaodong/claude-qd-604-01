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

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;

import com.bike.station.common.BizException;
import com.bike.station.common.ConflictException;
import com.bike.station.domain.Bike;
import com.bike.station.domain.BikeDict;
import com.bike.station.domain.InspectionDict;
import com.bike.station.domain.Pile;
import com.bike.station.domain.Station;
import com.bike.station.dto.InspectionDtos;
import com.bike.station.dto.RentalDtos;
import com.bike.station.dto.StationDtos;
import com.bike.station.dto.Views;
import com.bike.station.repository.BikeRepository;
import com.bike.station.repository.FaultHandlingRepository;
import com.bike.station.repository.InspectionBatchRepository;
import com.bike.station.repository.InspectionItemRepository;
import com.bike.station.repository.PileRepository;
import com.bike.station.repository.StationRepository;
import com.bike.station.service.InspectionService;
import com.bike.station.service.RentalService;
import com.bike.station.service.StationService;

/**
 * 桩位巡检端到端：批次快照、待隔离/已隔离、车离桩自动转隔离、借还调拨接入、
 * 复验恢复、一桩一条未完成处置、并发冲突（409）与站点看板计数。
 */
@SpringBootTest
@ActiveProfiles("test")
@Sql(scripts = "/reset-h2.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class InspectionFlowTests {

    @Autowired
    private InspectionService inspectionService;
    @Autowired
    private StationService stationService;
    @Autowired
    private RentalService rentalService;
    @Autowired
    private StationRepository stationRepository;
    @Autowired
    private PileRepository pileRepository;
    @Autowired
    private BikeRepository bikeRepository;
    @Autowired
    private InspectionBatchRepository batchRepository;
    @Autowired
    private InspectionItemRepository itemRepository;
    @Autowired
    private FaultHandlingRepository handlingRepository;

    private static final Long S1 = 1L;
    private static final Long S2 = 2L;
    private static final Long S3 = 3L;

    private static InspectionDtos.CheckRequest ok(String checker, int rev) {
        return new InspectionDtos.CheckRequest(checker, "NORMAL", "NORMAL", "NORMAL", null, null, rev);
    }

    private static InspectionDtos.CheckRequest fault(String checker, int rev) {
        return new InspectionDtos.CheckRequest(checker, "FAULT", "NORMAL", "NORMAL",
                "HIGH", "桩锁故障", rev);
    }

    private Views.InspectionItemView itemOf(Views.InspectionBatchView v, Long pileId) {
        return v.items().stream().filter(i -> i.pileId().equals(pileId)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------
    // 1. 建单固定桩位清单与占车快照；之后新增桩位不混入旧批次
    // ------------------------------------------------------------------
    @Test
    void batchSnapshotFreezesPileListAndOccupancy() {
        Views.InspectionBatchView batch = inspectionService.create(
                new InspectionDtos.BatchCreate(S2, null, "赵运营"));
        int pilesAtCreate = pileRepository.findByStationIdOrderByPileNoAsc(S2).size();
        assertEquals(pilesAtCreate, batch.totalCount());
        Views.InspectionItemView p1 = itemOf(batch, 107L);
        assertTrue(p1.snapshotOccupied());
        assertEquals("BK-1004", p1.snapshotBikeCode());
        Views.InspectionItemView p3 = itemOf(batch, 109L);
        assertFalse(p3.snapshotOccupied());

        // 建单后给 S002 新增一个桩位，旧批次明细数不变
        stationService.addPile(S2, new StationDtos.PileCreate("P9"));
        Views.InspectionBatchView again = inspectionService.detail(batch.id());
        assertEquals(pilesAtCreate, again.totalCount());
        assertEquals(pilesAtCreate, again.items().size());
        assertTrue(again.items().stream().noneMatch(i -> "P9".equals(i.pileNo())));
    }

    // ------------------------------------------------------------------
    // 2. 空桩故障立即隔离：还车/调拨落点不再可选；一桩一条未完成处置
    // ------------------------------------------------------------------
    @Test
    void emptyPileFaultIsolatesImmediatelyAndBlocksBikeEntry() {
        Views.InspectionBatchView batch = inspectionService.create(
                new InspectionDtos.BatchCreate(S2, null, "赵运营"));
        Long item109 = itemOf(batch, 109L).id();

        Views.InspectionBatchView after = inspectionService.check(
                batch.id(), item109, fault("赵运营", 0));
        Views.InspectionItemView checked = itemOf(after, 109L);
        assertEquals("FAULT", checked.result());
        assertEquals(InspectionDict.MODE_ISOLATED, checked.isolatedMode());
        assertEquals(BikeDict.PILE_ISOLATED, pileRepository.findById(109L).orElseThrow().getStatus());
        assertNotNull(checked.handling());
        assertEquals(InspectionDict.FH_PENDING, checked.handling().status());

        // 还车到已隔离桩 → 拒绝（BK-2001 的在途单 5 从 S3 借出）
        BizException ex = assertThrows(BizException.class,
                () -> rentalService.returnBike(5L, new RentalDtos.Return(109L)));
        assertTrue(ex.getMessage().contains("已隔离"));

        // 同一桩位不能再来一条未完成处置：新批次再报同桩故障被拒
        Views.InspectionBatchView batch2 = inspectionService.create(
                new InspectionDtos.BatchCreate(S2, null, "钱运营"));
        Long itemAgain = itemOf(batch2, 109L).id();
        assertThrows(BizException.class,
                () -> inspectionService.check(batch2.id(), itemAgain, fault("钱运营", 0)));
    }

    // ------------------------------------------------------------------
    // 3. 有车故障进入待隔离：不清落点、可借走，车离开自动转已隔离；期间不能还车/调入
    // ------------------------------------------------------------------
    @Test
    void occupiedPileFaultBecomesPendingIsolatedAndAutoIsolatesOnDeparture() {
        Views.InspectionBatchView batch = inspectionService.create(
                new InspectionDtos.BatchCreate(S2, null, "赵运营"));
        Long item107 = itemOf(batch, 107L).id();

        Views.InspectionBatchView after = inspectionService.check(
                batch.id(), item107, fault("赵运营", 0));
        assertEquals(InspectionDict.MODE_PENDING_ISOLATED, itemOf(after, 107L).isolatedMode());
        Pile pile = pileRepository.findById(107L).orElseThrow();
        assertEquals(BikeDict.PILE_PENDING_ISOLATED, pile.getStatus());
        // 车辆落点没有被清掉
        assertEquals(1004L, bikeRepository.findByPileId(107L).orElseThrow().getId());

        // 待隔离桩不接收新车：把在途 BK-2002（单 6）还到 107 → 拒绝（桩上还有车，先撞一桩一车，
        // 所以这里改用「先借走再还入」验证待隔离空桩也绝不接新车）
        // 桩上的车仍可被正常借走
        rentalService.borrow(new RentalDtos.Borrow(1L, 107L));
        assertEquals(BikeDict.PILE_ISOLATED, pileRepository.findById(107L).orElseThrow().getStatus());
        assertNull(bikeRepository.findByPileId(107L).orElse(null));

        // 车离开后该桩已隔离：在途车还进来同样被拒
        BizException ex = assertThrows(BizException.class,
                () -> rentalService.returnBike(6L, new RentalDtos.Return(107L)));
        assertTrue(ex.getMessage().contains("已隔离"));

        // 调拨也不能把车放进已隔离桩（BK-1005 在 108，调到 107）
        assertThrows(BizException.class, () -> stationService.transfer(
                new StationDtos.Transfer(1005L, 107L, null)));
    }

    // ------------------------------------------------------------------
    // 4. 待隔离桩上的车可由运营人员调出，调出即自动转已隔离
    // ------------------------------------------------------------------
    @Test
    void transferOutFromPendingIsolatedPileAutoIsolates() {
        Views.InspectionBatchView batch = inspectionService.create(
                new InspectionDtos.BatchCreate(S2, null, "赵运营"));
        inspectionService.check(batch.id(), itemOf(batch, 107L).id(), fault("赵运营", 0));
        // 把 BK-1004 从待隔离的 107 调到 S003 的空桩 112
        stationService.transfer(new StationDtos.Transfer(1004L, 112L, null));
        assertEquals(BikeDict.PILE_ISOLATED, pileRepository.findById(107L).orElseThrow().getStatus());
        Bike moved = bikeRepository.findById(1004L).orElseThrow();
        assertEquals(112L, moved.getPileId());
    }

    // ------------------------------------------------------------------
    // 5. 检查期间占车变化：提交时以最新事实决定待隔离/已隔离，无矛盾状态
    // ------------------------------------------------------------------
    @Test
    void checkUsesLatestOccupancyWhenBikeLeftDuringInspection() {
        Views.InspectionBatchView batch = inspectionService.create(
                new InspectionDtos.BatchCreate(S2, null, "赵运营"));
        Long item108 = itemOf(batch, 108L).id();
        // 页面打开后、提交前，桩上的 BK-1005 刚好被借走（桩位变 IDLE）
        Pile p108 = pileRepository.findById(108L).orElseThrow();
        p108.setStatus(BikeDict.PILE_IDLE);
        pileRepository.save(p108);
        Bike b1005 = bikeRepository.findById(1005L).orElseThrow();
        b1005.setStatus(BikeDict.BIKE_RIDING);
        b1005.setStationId(null);
        b1005.setPileId(null);
        bikeRepository.save(b1005);

        Views.InspectionBatchView after = inspectionService.check(
                batch.id(), item108, fault("赵运营", 0));
        // 最新事实是空桩 → 直接已隔离，而不是按建单快照挂「待隔离」
        assertEquals(InspectionDict.MODE_ISOLATED, itemOf(after, 108L).isolatedMode());
        assertEquals(BikeDict.PILE_ISOLATED, pileRepository.findById(108L).orElseThrow().getStatus());
        assertTrue(itemOf(after, 108L).occupancyChanged());
        assertNull(bikeRepository.findByPileId(108L).orElse(null));
    }

    // ------------------------------------------------------------------
    // 6. 处置 → 复验：不通过保留原因继续隔离；通过按真实占车恢复（空闲/占用）
    // ------------------------------------------------------------------
    @Test
    void handleThenRecheckRejectKeepsIsolationAndPassRestoresByRealOccupancy() {
        Views.InspectionBatchView batch = inspectionService.create(
                new InspectionDtos.BatchCreate(S2, null, "赵运营"));
        Views.InspectionBatchView checked = inspectionService.check(
                batch.id(), itemOf(batch, 109L).id(), fault("赵运营", 0));
        Long handlingId = itemOf(checked, 109L).handling().id();
        int hRev = itemOf(checked, 109L).handling().rev();

        // 处置前不能复验
        assertThrows(BizException.class, () -> inspectionService.recheck(handlingId,
                new InspectionDtos.RecheckRequest("钱运营", false, "先试试", hRev)));

        // 先处置，再复验不通过：不通过必须给原因
        Views.InspectionBatchView handled = inspectionService.handle(handlingId,
                new InspectionDtos.HandleRequest("孙维修", "更换锁体", hRev));
        assertEquals(InspectionDict.FH_HANDLED, itemOf(handled, 109L).handling().status());
        assertThrows(BizException.class, () -> inspectionService.recheck(handlingId,
                new InspectionDtos.RecheckRequest("钱运营", false, null, hRev + 1)));

        Views.InspectionBatchView rejected = inspectionService.recheck(handlingId,
                new InspectionDtos.RecheckRequest("钱运营", false, "锁体仍有卡滞", hRev + 1));
        assertEquals(InspectionDict.FH_RECHECK_REJECTED, itemOf(rejected, 109L).handling().status());
        assertEquals("锁体仍有卡滞", itemOf(rejected, 109L).handling().recheckNote());
        assertEquals(BikeDict.PILE_ISOLATED, pileRepository.findById(109L).orElseThrow().getStatus());

        // 再次处置 → 复验通过（此时桩空）→ 恢复为空闲
        int revAfterReject = itemOf(rejected, 109L).handling().rev();
        inspectionService.handle(handlingId,
                new InspectionDtos.HandleRequest("孙维修", "二次打磨锁扣", revAfterReject));
        Views.InspectionBatchView passed = inspectionService.recheck(handlingId,
                new InspectionDtos.RecheckRequest("钱运营", true, "复测合格", revAfterReject + 1));
        assertEquals(InspectionDict.FH_RECHECK_PASS, itemOf(passed, 109L).handling().status());
        assertEquals(BikeDict.PILE_IDLE, pileRepository.findById(109L).orElseThrow().getStatus());

        // 复验通过、桩上有车 → 恢复为占用（待隔离桩车没走就复验通过）
        Views.InspectionBatchView b2 = inspectionService.create(
                new InspectionDtos.BatchCreate(S3, null, "赵运营"));
        Views.InspectionBatchView c2 = inspectionService.check(
                b2.id(), itemOf(b2, 111L).id(), fault("赵运营", 0));
        Long h2 = itemOf(c2, 111L).handling().id();
        int r2 = itemOf(c2, 111L).handling().rev();
        assertEquals(BikeDict.PILE_PENDING_ISOLATED, pileRepository.findById(111L).orElseThrow().getStatus());
        inspectionService.handle(h2, new InspectionDtos.HandleRequest("孙维修", "修复", r2));
        inspectionService.recheck(h2,
                new InspectionDtos.RecheckRequest("钱运营", true, null, r2 + 1));
        assertEquals(BikeDict.PILE_OCCUPIED, pileRepository.findById(111L).orElseThrow().getStatus());
        assertEquals(1006L, bikeRepository.findByPileId(111L).orElseThrow().getId());
    }

    // ------------------------------------------------------------------
    // 7. 乐观锁并发：两名运营人员几乎同时登记同一桩，后一方 409 + 最新视图
    // ------------------------------------------------------------------
    @Test
    void concurrentCheckOneWinsOtherGetsConflictWithLatestView() throws Exception {
        Views.InspectionBatchView batch = inspectionService.create(
                new InspectionDtos.BatchCreate(S3, null, "赵运营"));
        Long itemId = itemOf(batch, 112L).id();

        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<String> outcomes = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        List<Views.InspectionBatchView> latestOnConflict =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    inspectionService.check(batch.id(), itemId, ok("检查人" + idx, 0));
                    outcomes.add("OK");
                } catch (ConflictException ce) {
                    outcomes.add("CONFLICT");
                    latestOnConflict.add((Views.InspectionBatchView) ce.getLatest());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(1, outcomes.stream().filter("OK"::equals).count());
        assertTrue(outcomes.contains("CONFLICT"));
        // 冲突带回的视图里该桩已有检查人与最新 rev（已生效记录不被覆盖）
        Views.InspectionBatchView latest = latestOnConflict.get(0);
        Views.InspectionItemView item = itemOf(latest, 112L);
        assertEquals("OK", item.result());
        assertNotNull(item.checkerName());
        assertEquals(1, item.rev());
    }

    // ------------------------------------------------------------------
    // 8. 处置与复验相撞：旧 rev 提交 409，已生效结论不被覆盖
    // ------------------------------------------------------------------
    @Test
    void handleAndRecheckCollisionReturnsConflict() {
        Views.InspectionBatchView batch = inspectionService.create(
                new InspectionDtos.BatchCreate(S2, null, "赵运营"));
        Views.InspectionBatchView checked = inspectionService.check(
                batch.id(), itemOf(batch, 109L).id(), fault("赵运营", 0));
        Long handlingId = itemOf(checked, 109L).handling().id();

        inspectionService.handle(handlingId, new InspectionDtos.HandleRequest("孙维修", "修复", 0));
        // 处置后 rev=1；另一页面仍拿 rev=0 去复验 → 冲突
        ConflictException ex = assertThrows(ConflictException.class,
                () -> inspectionService.recheck(handlingId,
                        new InspectionDtos.RecheckRequest("钱运营", true, null, 0)));
        Views.InspectionBatchView latest = (Views.InspectionBatchView) ex.getLatest();
        assertEquals(InspectionDict.FH_HANDLED, itemOf(latest, 109L).handling().status());
    }

    // ------------------------------------------------------------------
    // 9. 批次进度与看板计数：已隔离/待隔离/待复验数量一致，刷新后不变
    // ------------------------------------------------------------------
    @Test
    void boardCountersAndBatchProgressAreConsistent() {
        Views.InspectionBatchView batch = inspectionService.create(
                new InspectionDtos.BatchCreate(S3, null, "赵运营"));
        // 111 占桩故障（待隔离、待处置）；112 空桩故障（已隔离、待处置）；
        // 113 空桩故障后处置（待复验）；114/115 检查正常
        inspectionService.check(batch.id(), itemOf(batch, 111L).id(), fault("赵运营", 0));
        inspectionService.check(batch.id(), itemOf(batch, 112L).id(), fault("赵运营", 0));
        Views.InspectionBatchView c2 = inspectionService.check(
                batch.id(), itemOf(batch, 113L).id(), fault("赵运营", 0));
        Long h113 = itemOf(c2, 113L).handling().id();
        inspectionService.handle(h113, new InspectionDtos.HandleRequest("孙维修", "修复", 0));
        // 其余桩位全部查完 → 批次完成
        inspectionService.check(batch.id(), itemOf(c2, 114L).id(), ok("赵运营", 0));
        inspectionService.check(batch.id(), itemOf(c2, 115L).id(), ok("赵运营", 0));
        Views.InspectionBatchView done = inspectionService.detail(batch.id());
        assertEquals(InspectionDict.BATCH_COMPLETED, done.status());
        assertEquals(5, done.totalCount());
        assertEquals(5, done.checkedCount());
        assertEquals(3, done.faultCount());
        assertNotNull(done.completedAt());

        // 看板：S003 待隔离 1（111 桩上还有 BK-1006）、已隔离 2（112、113），待复验 1
        Views.StationCard card = stationService.cards(null).stream()
                .filter(c -> c.id().equals(S3)).findFirst().orElseThrow();
        assertEquals(2, card.isolatedCount());
        assertEquals(1, card.pendingRecheckCount());
        assertEquals(1, card.pendingIsolatedCount());

        // 列表里的站点计数与看板同源
        Views.InspectionListItem row = inspectionService.list(S3, null).stream()
                .filter(r -> r.id().equals(batch.id())).findFirst().orElseThrow();
        assertEquals(card.isolatedCount(), row.stationIsolatedCount());
        assertEquals(card.pendingRecheckCount(), row.stationPendingRecheckCount());
    }

    // ------------------------------------------------------------------
    // 10. 同一站点可建历史批次；批次互不影响
    // ------------------------------------------------------------------
    @Test
    void multipleHistoricalBatchesAllowedPerStation() {
        inspectionService.create(new InspectionDtos.BatchCreate(S2, "第一批", "赵运营"));
        inspectionService.create(new InspectionDtos.BatchCreate(S2, "第二批", "钱运营"));
        List<Views.InspectionListItem> rows = inspectionService.list(S2, null);
        assertTrue(rows.size() >= 2);
    }
}
