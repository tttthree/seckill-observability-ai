package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.SeckillProperties;
import com.hmdp.dto.IncidentReport;
import com.hmdp.entity.Incident;
import com.hmdp.enums.IncidentSeverity;
import com.hmdp.enums.IncidentSource;
import com.hmdp.enums.IncidentStatus;
import com.hmdp.enums.IncidentType;
import com.hmdp.mapper.IncidentMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 故障事件去重/聚合/恢复语义单元测试（不依赖 Redis 与 MySQL）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IncidentServiceImplTest {

    @Mock
    private IncidentMapper incidentMapper;

    private IncidentServiceImpl service;

    @BeforeEach
    void setUp() {
        // MyBatis-Plus 的 lambda 列名解析依赖 TableInfo 缓存，单元测试中没有 MyBatis 容器，需手动初始化
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Incident.class);

        service = new IncidentServiceImpl();
        ReflectionTestUtils.setField(service, "baseMapper", incidentMapper);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(service, "seckillProperties", new SeckillProperties());
    }

    /** Case 2：首次异常 → 产生一条 OPEN 故障事件 */
    @Test
    void shouldCreateOpenIncidentOnFirstDetection() {
        when(incidentMapper.update(any(), any())).thenReturn(0);
        when(incidentMapper.insert(any(Incident.class))).thenReturn(1);

        Incident created = service.report(inventoryMismatchReport());

        assertNotNull(created);
        assertEquals(IncidentType.INVENTORY_MISMATCH, created.getIncidentType());
        assertEquals(IncidentSeverity.HIGH, created.getSeverity());
        assertEquals(IncidentSource.RECONCILE, created.getSource());
        assertEquals(IncidentStatus.OPEN, created.getStatus());
        assertEquals("voucher:99", created.getBusinessKey());
        assertEquals("INVENTORY_MISMATCH:voucher:99", created.getOpenKey());
        assertEquals(Long.valueOf(99L), created.getRelatedVoucherId());
        assertEquals(1, created.getOccurrenceCount());
        assertNotNull(created.getFirstDetectedAt());
        assertNotNull(created.getLastDetectedAt());
        assertNull(created.getResolvedAt());
        assertTrue(created.getSnapshot().contains("\"deviation\":2"));
        verify(incidentMapper, never()).selectOne(any());
    }

    /** Case 3：持续异常 → 只聚合更新，不新增行，且使用原子自增 */
    @SuppressWarnings("unchecked")
    @Test
    void shouldAggregateRepeatedDetectionWithoutCreatingNewRow() {
        when(incidentMapper.update(any(), any())).thenReturn(1);
        when(incidentMapper.selectOne(any())).thenReturn(new Incident()
                .setId(7L)
                .setSeverity(IncidentSeverity.HIGH));

        assertNull(service.report(inventoryMismatchReport()));
        assertNull(service.report(inventoryMismatchReport()));

        verify(incidentMapper, never()).insert(any(Incident.class));

        ArgumentCaptor<Wrapper<Incident>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(incidentMapper, times(2)).update(any(), captor.capture());
        LambdaUpdateWrapper<Incident> wrapper =
                (LambdaUpdateWrapper<Incident>) captor.getValue();
        assertTrue(wrapper.getSqlSet().contains("occurrence_count = occurrence_count + 1"),
                "持续异常必须原子递增 occurrence_count: " + wrapper.getSqlSet());
        assertTrue(wrapper.getSqlSet().contains("last_detected_at"),
                "持续异常必须刷新 last_detected_at: " + wrapper.getSqlSet());
    }

    /** 并发竞态：插入撞唯一索引时回退为聚合更新，不抛异常 */
    @Test
    void shouldFallBackToAggregationWhenConcurrentInsertConflicts() {
        when(incidentMapper.update(any(), any())).thenReturn(0, 1);
        when(incidentMapper.insert(any(Incident.class)))
                .thenThrow(new DuplicateKeyException("uk_incident_open"));

        assertNull(service.report(inventoryMismatchReport()));

        verify(incidentMapper, times(1)).insert(any(Incident.class));
        verify(incidentMapper, times(2)).update(any(), any());
    }

    /** Case 4：恢复 → OPEN 置为 RESOLVED 并释放 open_key；重复调用幂等 */
    @SuppressWarnings("unchecked")
    @Test
    void shouldResolveOpenIncidentAndReleaseOpenKey() {
        when(incidentMapper.update(any(), any())).thenReturn(1);
        assertTrue(service.resolve(IncidentType.INVENTORY_MISMATCH, "voucher:99"));

        when(incidentMapper.update(any(), any())).thenReturn(0);
        assertFalse(service.resolve(IncidentType.INVENTORY_MISMATCH, "voucher:99"));

        ArgumentCaptor<Wrapper<Incident>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(incidentMapper, times(2)).update(any(), captor.capture());
        String sqlSet = ((LambdaUpdateWrapper<Incident>) captor.getAllValues().get(0)).getSqlSet();
        assertTrue(sqlSet.contains("status"), sqlSet);
        assertTrue(sqlSet.contains("resolved_at"), sqlSet);
        assertTrue(sqlSet.contains("open_key"), "恢复时必须释放 open_key 以允许复发建单: " + sqlSet);
    }

    /** Case 4 续：恢复后再次发生 → 允许创建新的故障事件（open_key 已释放） */
    @Test
    void shouldCreateNewIncidentAfterResolve() {
        when(incidentMapper.update(any(), any())).thenReturn(0);
        when(incidentMapper.insert(any(Incident.class))).thenReturn(1);

        Incident created = service.report(inventoryMismatchReport());

        assertNotNull(created);
        assertEquals("INVENTORY_MISMATCH:voucher:99", created.getOpenKey());
    }

    /** 故障级别只升不降 */
    @Test
    void shouldEscalateSeverityOnlyUpward() {
        when(incidentMapper.update(any(), any())).thenReturn(1);
        when(incidentMapper.selectOne(any())).thenReturn(new Incident()
                .setId(3L)
                .setSeverity(IncidentSeverity.MEDIUM));

        service.report(inventoryMismatchReport()); // HIGH > MEDIUM

        verify(incidentMapper, times(2)).update(any(), any());
    }

    /** 级别不降级：既有 CRITICAL 不被 HIGH 覆盖 */
    @Test
    void shouldNotDowngradeExistingSeverity() {
        when(incidentMapper.update(any(), any())).thenReturn(1);
        when(incidentMapper.selectOne(any())).thenReturn(new Incident()
                .setId(3L)
                .setSeverity(IncidentSeverity.CRITICAL));

        service.report(inventoryMismatchReport());

        verify(incidentMapper, times(1)).update(any(), any());
    }

    /** 持久化失败不得抛异常影响秒杀主链路 */
    @Test
    void shouldNeverThrowWhenPersistenceFails() {
        when(incidentMapper.update(any(), any())).thenThrow(new RuntimeException("db down"));

        assertNull(service.report(inventoryMismatchReport()));
        assertFalse(service.resolve(IncidentType.INVENTORY_MISMATCH, "voucher:99"));
    }

    /** 参数不完整时不触碰数据库 */
    @Test
    void shouldIgnoreIncompleteReport() {
        assertNull(service.report(new IncidentReport().setBusinessKey("voucher:99")));
        assertNull(service.report(new IncidentReport().setIncidentType(IncidentType.DEAD_LETTER)));
        assertNull(service.report(null));

        verifyNoInteractions(incidentMapper);
    }

    /** Case 1：无异常时不产生故障事件，列表为空 */
    @Test
    void shouldReturnEmptyListWhenNoAnomalyWasDetected() {
        when(incidentMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertTrue(service.listIncidents(IncidentStatus.OPEN, null, null, null).isEmpty());
        verify(incidentMapper, never()).insert(any(Incident.class));
    }

    private IncidentReport inventoryMismatchReport() {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("voucher_id", 99L);
        evidence.put("redis_stock", 5);
        evidence.put("db_stock", 7);
        evidence.put("deviation", 2);

        return new IncidentReport()
                .setIncidentType(IncidentType.INVENTORY_MISMATCH)
                .setSource(IncidentSource.RECONCILE)
                .setSeverity(IncidentSeverity.HIGH)
                .setBusinessKey("voucher:99")
                .setRelatedVoucherId(99L)
                .setTitle("Redis 与 MySQL 库存持续不一致（voucherId=99）")
                .setDescription("连续两轮对账确认偏差：redisStock=5, dbStock=7, deviation=2")
                .setEvidence(evidence);
    }
}
