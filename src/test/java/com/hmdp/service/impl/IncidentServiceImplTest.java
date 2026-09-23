package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
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

    /** installConditionalSeverityDatabase 维护的"数据库当前级别" */
    private IncidentSeverity storedSeverity;
    /** 条件更新被尝试升级到的级别序列 */
    private final List<IncidentSeverity> escalationAttempts = new ArrayList<>();
    /** 条件更新真正写入的次数 */
    private int escalationWrites;

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

        assertNull(service.report(inventoryMismatchReport()));
        assertNull(service.report(inventoryMismatchReport()));

        verify(incidentMapper, never()).insert(any(Incident.class));

        ArgumentCaptor<Wrapper<Incident>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(incidentMapper, times(4)).update(any(), captor.capture());

        long aggregateStatements = captor.getAllValues().stream()
                .map(wrapper -> ((LambdaUpdateWrapper<Incident>) wrapper).getSqlSet())
                .filter(sqlSet -> sqlSet.contains("occurrence_count = occurrence_count + 1"))
                .count();
        assertEquals(2, aggregateStatements, "两轮持续异常必须各产生一次原子自增，而不是两行新记录");

        String aggregateSqlSet = captor.getAllValues().stream()
                .map(wrapper -> ((LambdaUpdateWrapper<Incident>) wrapper).getSqlSet())
                .filter(sqlSet -> sqlSet.contains("occurrence_count = occurrence_count + 1"))
                .findFirst()
                .orElseThrow();
        assertTrue(aggregateSqlSet.contains("last_detected_at"),
                "持续异常必须刷新 last_detected_at: " + aggregateSqlSet);
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

    /** 故障级别升级必须是单条条件 UPDATE，不存在 SELECT→比较→UPDATE 竞态 */
    @SuppressWarnings("unchecked")
    @Test
    void shouldEscalateSeverityWithSingleConditionalUpdate() {
        when(incidentMapper.update(any(), any())).thenReturn(1);

        service.report(inventoryMismatchReport()); // HIGH

        ArgumentCaptor<Wrapper<Incident>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(incidentMapper, times(2)).update(any(), captor.capture());
        verify(incidentMapper, never()).selectOne(any());

        LambdaUpdateWrapper<Incident> escalation =
                (LambdaUpdateWrapper<Incident>) captor.getAllValues().get(1);
        assertTrue(escalation.getSqlSet().contains("severity"),
                "升级语句必须写入 severity: " + escalation.getSqlSet());
        assertTrue(escalation.getSqlSegment().contains("CASE severity WHEN 'LOW' THEN 1"),
                "升级条件必须由数据库比较级别序号: " + escalation.getSqlSegment());
        assertTrue(escalation.getSqlSegment().contains("< #{"),
                "级别比较必须使用绑定参数而非字符串拼接: " + escalation.getSqlSegment());
        assertTrue(escalation.getParamNameValuePairs().containsValue(IncidentSeverity.HIGH.getRank()),
                "升级条件必须绑定本次级别序号: " + escalation.getParamNameValuePairs());
    }

    /**
     * 并发语义：用"条件更新"的模拟数据库替代真实 MySQL，
     * 断言任意到达顺序下 severity 都不会降级。
     */
    @Test
    void shouldNeverDowngradeSeverityUnderAnyConcurrentOrder() {
        installConditionalSeverityDatabase(IncidentSeverity.MEDIUM);

        // 顺序一：先 HIGH 再 CRITICAL
        service.report(reportWithSeverity(IncidentSeverity.HIGH));
        service.report(reportWithSeverity(IncidentSeverity.CRITICAL));
        assertEquals(IncidentSeverity.CRITICAL, storedSeverity);

        // 顺序二（反向到达）：先 CRITICAL 再 HIGH —— HIGH 的条件更新必须匹配 0 行
        installConditionalSeverityDatabase(IncidentSeverity.MEDIUM);
        service.report(reportWithSeverity(IncidentSeverity.CRITICAL));
        service.report(reportWithSeverity(IncidentSeverity.HIGH));

        assertEquals(IncidentSeverity.CRITICAL, storedSeverity, "任何并发顺序都不得把 CRITICAL 降回 HIGH");
        verify(incidentMapper, never()).selectOne(any());
    }

    /** 同级别重复上报不产生新的级别写入（条件不满足，影响 0 行） */
    @Test
    void shouldNotRewriteSameSeverity() {
        installConditionalSeverityDatabase(IncidentSeverity.HIGH);

        service.report(reportWithSeverity(IncidentSeverity.HIGH));

        assertEquals(IncidentSeverity.HIGH, storedSeverity);
        assertEquals(1, escalationAttempts.size());
        assertEquals(0, escalationWrites, "同级别不应产生级别写入");
    }

    /** 持久化失败不得抛异常影响秒杀主链路 */
    @Test
    void shouldNeverThrowWhenPersistenceFails() {
        when(incidentMapper.update(any(), any())).thenThrow(new RuntimeException("db down"));

        assertNull(service.report(inventoryMismatchReport()));
        assertFalse(service.resolve(IncidentType.INVENTORY_MISMATCH, "voucher:99"));
    }

    /** 参数不完整时不触碰数据库（含 source 缺失） */
    @Test
    void shouldIgnoreIncompleteReport() {
        assertNull(service.report(new IncidentReport().setBusinessKey("voucher:99")));
        assertNull(service.report(new IncidentReport().setIncidentType(IncidentType.DEAD_LETTER)));
        assertNull(service.report(new IncidentReport()
                .setIncidentType(IncidentType.DEAD_LETTER)
                .setBusinessKey("voucher:99")));
        assertNull(service.report(inventoryMismatchReport().setSource(null)));
        assertNull(service.report(null));

        verifyNoInteractions(incidentMapper);
    }

    /** 运维查询必须显式失败：数据库异常不得被伪装成空列表 */
    @Test
    void shouldPropagateDatabaseFailureFromListIncidents() {
        when(incidentMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> service.listIncidents(IncidentStatus.OPEN, null, null, null));
        assertEquals("db down", thrown.getMessage());
    }

    /** 运维查询必须显式失败：数据库异常不得被伪装成 null（详情） */
    @Test
    void shouldPropagateDatabaseFailureFromGetIncident() {
        when(incidentMapper.selectById(any())).thenThrow(new RuntimeException("db down"));

        assertThrows(RuntimeException.class, () -> service.getIncident(7L));
    }

    /** 检测链路查询保持 fail-open，避免一次数据库抖动中断其它故障的状态同步 */
    @Test
    void shouldKeepDetectorQueryFailOpen() {
        when(incidentMapper.selectList(any())).thenThrow(new RuntimeException("db down"));

        assertTrue(service.listOpenIncidents(IncidentType.DEAD_LETTER).isEmpty());
    }

    /** Case 1：无异常时不产生故障事件，列表为空 */
    @Test
    void shouldReturnEmptyListWhenNoAnomalyWasDetected() {
        when(incidentMapper.selectList(any())).thenReturn(Collections.emptyList());

        assertTrue(service.listIncidents(IncidentStatus.OPEN, null, null, null).isEmpty());
        verify(incidentMapper, never()).insert(any(Incident.class));
    }

    private IncidentReport inventoryMismatchReport() {
        return reportWithSeverity(IncidentSeverity.HIGH);
    }

    private IncidentReport reportWithSeverity(IncidentSeverity severity) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("voucher_id", 99L);
        evidence.put("redis_stock", 5);
        evidence.put("db_stock", 7);
        evidence.put("deviation", 2);

        return new IncidentReport()
                .setIncidentType(IncidentType.INVENTORY_MISMATCH)
                .setSource(IncidentSource.RECONCILE)
                .setSeverity(severity)
                .setBusinessKey("voucher:99")
                .setRelatedVoucherId(99L)
                .setTitle("Redis 与 MySQL 库存持续不一致（voucherId=99）")
                .setDescription("连续两轮对账确认偏差：redisStock=5, dbStock=7, deviation=2")
                .setEvidence(evidence);
    }

    /**
     * 用模拟数据库复现 MySQL 上"条件更新"的真实语义：
     * 只有库中级别序号严格小于本次级别序号时才写入，否则影响 0 行。
     * 借此在单测中验证并发到达顺序不影响最终级别。
     */
    private void installConditionalSeverityDatabase(IncidentSeverity initialSeverity) {
        storedSeverity = initialSeverity;
        escalationAttempts.clear();
        escalationWrites = 0;

        // 必须用 doAnswer 而非 when().thenAnswer()：后者在重复安装桩时会先执行上一个 answer，
        // 此时参数是匹配器而非真实参数，会触发空指针。
        doAnswer(invocation -> {
            Wrapper<Incident> wrapper = invocation.getArgument(1);
            String sqlSet = ((AbstractWrapper<Incident, ?, ?>) wrapper).getSqlSet();
            boolean escalationStatement = sqlSet != null && sqlSet.contains("severity");

            if (!escalationStatement) {
                return 1; // 聚合更新：视为命中既有 OPEN 事件
            }

            // apply("{0}", ...) 的绑定参数由 MyBatis-Plus 在生成 SQL 片段时惰性物化，
            // 单测里没有真实 SQL 生成过程，需先取一次 sqlSegment 触发物化再读取参数。
            wrapper.getSqlSegment();

            IncidentSeverity target = targetSeverity(wrapper);
            escalationAttempts.add(target);
            if (target != null && storedSeverity != null && storedSeverity.getRank() < target.getRank()) {
                storedSeverity = target;
                escalationWrites++;
                return 1;
            }
            return 0;
        }).when(incidentMapper).update(any(), any());
    }

    /** 从条件更新的绑定参数中还原本次上报的级别序号 */
    private static IncidentSeverity targetSeverity(Wrapper<Incident> wrapper) {
        Map<String, Object> params = ((AbstractWrapper<Incident, ?, ?>) wrapper).getParamNameValuePairs();
        for (Object value : params.values()) {
            if (value instanceof Integer) {
                int rank = (Integer) value;
                for (IncidentSeverity severity : IncidentSeverity.values()) {
                    if (severity.getRank() == rank) {
                        return severity;
                    }
                }
            }
        }
        return null;
    }
}
