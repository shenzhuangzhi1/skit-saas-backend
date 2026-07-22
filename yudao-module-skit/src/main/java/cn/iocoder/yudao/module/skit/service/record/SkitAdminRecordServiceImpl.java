package cn.iocoder.yudao.module.skit.service.record;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordPageReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordRespVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitAdminRecordSaveReqVO;
import cn.iocoder.yudao.module.skit.controller.admin.record.vo.SkitDashboardSummaryRespVO;
import cn.iocoder.yudao.module.skit.dal.dataobject.record.SkitAdminRecordDO;
import cn.iocoder.yudao.module.skit.dal.mysql.record.SkitAdminRecordMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 短剧 SaaS 通用记录 Service 实现。
 */
@Service
@Validated
public class SkitAdminRecordServiceImpl implements SkitAdminRecordService {

    @Resource
    private SkitAdminRecordMapper skitAdminRecordMapper;
    @Resource
    private ObjectMapper objectMapper;

    @Override
    public Long createRecord(SkitAdminRecordSaveReqVO createReqVO) {
        SkitAdminRecordDO record = buildDO(createReqVO);
        skitAdminRecordMapper.insert(record);
        return record.getId();
    }

    @Override
    public void updateRecord(SkitAdminRecordSaveReqVO updateReqVO) {
        if (updateReqVO.getId() == null || skitAdminRecordMapper.selectById(updateReqVO.getId()) == null) {
            throw new IllegalArgumentException("短剧记录不存在");
        }
        SkitAdminRecordDO record = buildDO(updateReqVO);
        skitAdminRecordMapper.updateById(record);
    }

    @Override
    public void deleteRecord(Long id) {
        if (skitAdminRecordMapper.selectById(id) == null) {
            throw new IllegalArgumentException("短剧记录不存在");
        }
        skitAdminRecordMapper.deleteById(id);
    }

    @Override
    public void deleteRecordList(List<Long> ids) {
        if (CollUtil.isEmpty(ids)) {
            return;
        }
        skitAdminRecordMapper.deleteByIds(ids);
    }

    @Override
    public SkitAdminRecordRespVO getRecord(Long id) {
        return convert(skitAdminRecordMapper.selectById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<SkitAdminRecordRespVO> getRecordPage(SkitAdminRecordPageReqVO pageReqVO) {
        PageResult<SkitAdminRecordDO> pageResult = skitAdminRecordMapper.selectPage(pageReqVO);
        List<SkitAdminRecordRespVO> list = new ArrayList<>(pageResult.getList().size());
        for (SkitAdminRecordDO record : pageResult.getList()) {
            list.add(convert(record));
        }
        return new PageResult<>(list, pageResult.getTotal());
    }

    @Override
    public SkitDashboardSummaryRespVO getDashboardSummary() {
        SkitDashboardSummaryRespVO summary = new SkitDashboardSummaryRespVO();
        summary.setTotalMembers(skitAdminRecordMapper.selectCountByPageKey("user"));
        summary.setTotalAdCount(skitAdminRecordMapper.selectCountByPageKey("adRecord"));
        summary.setTotalRevenue(BigDecimal.ZERO);
        summary.setTotalProfit(BigDecimal.ZERO);
        summary.setTodayRegisterCount(0L);
        summary.setTodayAdCount(0L);
        summary.setTodayRevenue(BigDecimal.ZERO);
        summary.setTodayProfit(BigDecimal.ZERO);
        summary.setRewardExchange(BigDecimal.ZERO);
        summary.setScorePerYuan(1000L);
        return summary;
    }

    private SkitAdminRecordDO buildDO(SkitAdminRecordSaveReqVO reqVO) {
        return SkitAdminRecordDO.builder()
                .id(reqVO.getId())
                .pageKey(reqVO.getPageKey())
                .rowKey(StrUtil.blankToDefault(reqVO.getRowKey(),
                        reqVO.getPageKey() + "-custom-" + System.currentTimeMillis()))
                .recordData(toJson(reqVO.getRecordData()))
                .status(reqVO.getStatus() == null ? 0 : reqVO.getStatus())
                .sort(reqVO.getSort() == null ? 0 : reqVO.getSort())
                .build();
    }

    private SkitAdminRecordRespVO convert(SkitAdminRecordDO record) {
        if (record == null) {
            return null;
        }
        SkitAdminRecordRespVO respVO = new SkitAdminRecordRespVO();
        respVO.setId(record.getId());
        respVO.setPageKey(record.getPageKey());
        respVO.setRowKey(record.getRowKey());
        respVO.setStatus(record.getStatus());
        respVO.setSort(record.getSort());
        respVO.setCreateTime(record.getCreateTime());
        respVO.setUpdateTime(record.getUpdateTime());
        respVO.setRecordData(fromJson(record.getRecordData()));
        return respVO;
    }

    private String toJson(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data == null ? Collections.emptyMap() : data);
        } catch (Exception e) {
            throw new IllegalArgumentException("短剧记录 JSON 序列化失败", e);
        }
    }

    private Map<String, Object> fromJson(String data) {
        if (StrUtil.isBlank(data)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(data, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            return parseLegacyRecordData(data);
        }
    }

    private Map<String, Object> parseLegacyRecordData(String data) {
        Map<String, Object> row = new LinkedHashMap<>();
        String content = StrUtil.trim(data);
        if (content.startsWith("{") && content.endsWith("}") && content.contains("=")) {
            String body = content.substring(1, content.length() - 1);
            String[] pairs = body.split(", ");
            for (String pair : pairs) {
                int splitIndex = pair.indexOf('=');
                if (splitIndex <= 0) {
                    continue;
                }
                String key = pair.substring(0, splitIndex).trim();
                String value = pair.substring(splitIndex + 1).trim();
                if (StrUtil.isNotBlank(key)) {
                    row.put(key, value);
                }
            }
        }
        if (row.isEmpty()) {
            row.put("raw", data);
        }
        return row;
    }

}
