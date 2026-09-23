package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.SeckillProperties;
import com.hmdp.constant.IncidentConstants;
import com.hmdp.dto.IncidentReport;
import com.hmdp.entity.Incident;
import com.hmdp.enums.IncidentSeverity;
import com.hmdp.enums.IncidentStatus;
import com.hmdp.enums.IncidentType;
import com.hmdp.mapper.IncidentMapper;
import com.hmdp.service.IncidentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * <p>
 * 统一故障事件服务实现类
 * </p>
 *
 * <p>
 * 去重/聚合策略：
 * </p>
 * <ol>
 *   <li>open_key = incidentType:businessKey，仅在 OPEN 时写入，RESOLVED 时置 NULL，
 *       由唯一索引 uk_incident_open 保证同一故障同时只有一条 OPEN 事件；</li>
 *   <li>上报先尝试原子聚合更新（occurrence_count + 1），影响行数为 0 才插入新事件；</li>
 *   <li>并发插入撞唯一索引时回退为聚合更新，不对外抛异常。</li>
 * </ol>
 */
@Slf4j
@Service
public class IncidentServiceImpl extends ServiceImpl<IncidentMapper, Incident> implements IncidentService {

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private SeckillProperties seckillProperties;

    @Override
    public Incident report(IncidentReport report) {
        if (report == null
                || report.getIncidentType() == null
                || !StringUtils.hasText(report.getBusinessKey())) {
            log.warn("故障事件上报参数不完整，已忽略");
            return null;
        }

        String openKey = buildOpenKey(report.getIncidentType(), report.getBusinessKey());
        if (openKey == null) {
            log.warn("故障事件 open_key 超出长度上限，已忽略 type={}, businessKey={}",
                    report.getIncidentType(), report.getBusinessKey());
            return null;
        }

        LocalDateTime now = LocalDateTime.now();

        try {
            if (aggregateOpenIncident(openKey, report, now) > 0) {
                escalateSeverityIfNeeded(openKey, report.getSeverity());
                return null;
            }

            Incident incident = buildIncident(openKey, report, now);
            if (!save(incident)) {
                log.warn("故障事件写入失败（影响行数为 0） type={}, businessKey={}",
                        report.getIncidentType(), report.getBusinessKey());
                return null;
            }

            log.warn("新建故障事件 id={}, type={}, severity={}, businessKey={}, title={}",
                    incident.getId(), incident.getIncidentType(), incident.getSeverity(),
                    incident.getBusinessKey(), incident.getTitle());
            return incident;

        } catch (DuplicateKeyException e) {
            // 并发下已被其他线程/实例创建同键 OPEN 事件，回退为聚合更新
            try {
                int retried = aggregateOpenIncident(openKey, report, now);
                log.info("故障事件并发创建冲突，已聚合到既有 OPEN 事件 openKey={}, rows={}", openKey, retried);
            } catch (Exception retryError) {
                log.warn("故障事件并发冲突后聚合失败 openKey={}", openKey, retryError);
            }
            return null;

        } catch (Exception e) {
            // 故障记录失败不得影响秒杀主链路（数据库本身可能就是故障源）
            log.warn("故障事件上报失败 type={}, businessKey={}",
                    report.getIncidentType(), report.getBusinessKey(), e);
            return null;
        }
    }

    @Override
    public boolean resolve(IncidentType incidentType, String businessKey) {
        if (incidentType == null || !StringUtils.hasText(businessKey)) {
            return false;
        }

        String openKey = buildOpenKey(incidentType, businessKey);
        if (openKey == null) {
            return false;
        }

        try {
            int rows = baseMapper.update(null, Wrappers.<Incident>lambdaUpdate()
                    .eq(Incident::getOpenKey, openKey)
                    .eq(Incident::getStatus, IncidentStatus.OPEN)
                    .set(Incident::getStatus, IncidentStatus.RESOLVED)
                    .set(Incident::getResolvedAt, LocalDateTime.now())
                    .set(Incident::getOpenKey, null));

            if (rows > 0) {
                log.info("故障事件已恢复 type={}, businessKey={}", incidentType, businessKey);
                return true;
            }
            return false;

        } catch (Exception e) {
            log.warn("故障事件恢复标记失败 type={}, businessKey={}", incidentType, businessKey, e);
            return false;
        }
    }

    @Override
    public List<Incident> listIncidents(IncidentStatus status, IncidentType type,
                                        Long voucherId, Integer limit) {
        try {
            return list(Wrappers.<Incident>lambdaQuery()
                    .eq(status != null, Incident::getStatus, status)
                    .eq(type != null, Incident::getIncidentType, type)
                    .eq(voucherId != null, Incident::getRelatedVoucherId, voucherId)
                    .orderByDesc(Incident::getLastDetectedAt)
                    .last("LIMIT " + normalizeLimit(limit)));
        } catch (Exception e) {
            log.warn("故障事件列表查询失败", e);
            return Collections.emptyList();
        }
    }

    @Override
    public Incident getIncident(Long incidentId) {
        if (incidentId == null) {
            return null;
        }
        try {
            return getById(incidentId);
        } catch (Exception e) {
            log.warn("故障事件详情查询失败 id={}", incidentId, e);
            return null;
        }
    }

    @Override
    public List<Incident> listOpenIncidents(IncidentType incidentType) {
        try {
            return list(Wrappers.<Incident>lambdaQuery()
                    .eq(Incident::getStatus, IncidentStatus.OPEN)
                    .eq(incidentType != null, Incident::getIncidentType, incidentType)
                    .orderByDesc(Incident::getLastDetectedAt));
        } catch (Exception e) {
            log.warn("OPEN 故障事件查询失败", e);
            return Collections.emptyList();
        }
    }

    // ==================== 内部实现 ====================

    /**
     * 原子聚合到既有 OPEN 事件，返回影响行数（0 表示不存在 OPEN 事件）。
     */
    private int aggregateOpenIncident(String openKey, IncidentReport report, LocalDateTime now) {
        return baseMapper.update(null, Wrappers.<Incident>lambdaUpdate()
                .eq(Incident::getOpenKey, openKey)
                .eq(Incident::getStatus, IncidentStatus.OPEN)
                .setSql("occurrence_count = occurrence_count + 1")
                .set(Incident::getLastDetectedAt, now)
                .set(Incident::getDescription,
                        truncate(report.getDescription(), IncidentConstants.DESCRIPTION_MAX_LENGTH))
                .set(Incident::getSnapshot, toJson(report.getEvidence())));
    }

    /**
     * 故障级别只升不降：同一事件被更高等级的证据再次命中时升级。
     */
    private void escalateSeverityIfNeeded(String openKey, IncidentSeverity severity) {
        if (severity == null) {
            return;
        }
        Incident open = baseMapper.selectOne(Wrappers.<Incident>lambdaQuery()
                .eq(Incident::getOpenKey, openKey));
        if (open == null || open.getSeverity() == null || severity.getRank() <= open.getSeverity().getRank()) {
            return;
        }
        baseMapper.update(null, Wrappers.<Incident>lambdaUpdate()
                .eq(Incident::getId, open.getId())
                .set(Incident::getSeverity, severity));
        log.info("故障事件级别升级 id={}, {} -> {}", open.getId(), open.getSeverity(), severity);
    }

    private Incident buildIncident(String openKey, IncidentReport report, LocalDateTime now) {
        String title = StringUtils.hasText(report.getTitle())
                ? report.getTitle()
                : report.getIncidentType().getLabel() + "：" + report.getBusinessKey();

        return new Incident()
                .setIncidentType(report.getIncidentType())
                .setSeverity(report.getSeverity() == null ? IncidentSeverity.MEDIUM : report.getSeverity())
                .setSource(report.getSource())
                .setStatus(IncidentStatus.OPEN)
                .setBusinessKey(report.getBusinessKey())
                .setOpenKey(openKey)
                .setTitle(truncate(title, IncidentConstants.TITLE_MAX_LENGTH))
                .setDescription(truncate(report.getDescription(), IncidentConstants.DESCRIPTION_MAX_LENGTH))
                .setRelatedVoucherId(report.getRelatedVoucherId())
                .setOccurrenceCount(1)
                .setFirstDetectedAt(now)
                .setLastDetectedAt(now)
                .setSnapshot(toJson(report.getEvidence()));
    }

    private String buildOpenKey(IncidentType type, String businessKey) {
        String openKey = type.getCode() + IncidentConstants.OPEN_KEY_SEPARATOR + businessKey;
        if (openKey.length() > IncidentConstants.OPEN_KEY_MAX_LENGTH) {
            return null;
        }
        return openKey;
    }

    private int normalizeLimit(Integer limit) {
        int defaultValue = seckillProperties.getIncident().getDefaultListLimit();
        int maxValue = seckillProperties.getIncident().getMaxListLimit();
        int size = limit == null ? defaultValue : limit;
        if (size < 1) {
            size = 1;
        }
        return Math.min(size, maxValue);
    }

    private String toJson(Map<String, Object> evidence) {
        if (evidence == null || evidence.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(evidence);
        } catch (JsonProcessingException e) {
            log.warn("故障事件证据序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
