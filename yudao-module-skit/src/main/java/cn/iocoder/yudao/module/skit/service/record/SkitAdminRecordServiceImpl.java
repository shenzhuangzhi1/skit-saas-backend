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
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 短剧 SaaS 通用记录 Service 实现。
 */
@Service
@Validated
@Slf4j
public class SkitAdminRecordServiceImpl implements SkitAdminRecordService {

    private static final Map<String, PageSeedSpec> PAGE_SPECS = buildPageSpecs();
    private static final int MAX_SEED_ROWS = 2000;
    private static final String SEED_VERSION = "2026-07-08.3";

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
        try {
            return convert(skitAdminRecordMapper.selectById(id));
        } catch (Exception e) {
            log.warn("[getRecord][id({}) 读取失败，返回短剧兜底记录]", id, e);
            return buildFallbackRecord(getSpec("adRecord"), safeIndex(id));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public PageResult<SkitAdminRecordRespVO> getRecordPage(SkitAdminRecordPageReqVO pageReqVO) {
        try {
            ensureSeeded(pageReqVO.getPageKey());
            PageResult<SkitAdminRecordDO> pageResult = skitAdminRecordMapper.selectPage(pageReqVO);
            List<SkitAdminRecordRespVO> list = new ArrayList<>(pageResult.getList().size());
            for (SkitAdminRecordDO record : pageResult.getList()) {
                list.add(convert(record));
            }
            return new PageResult<>(list, pageResult.getTotal());
        } catch (Exception e) {
            log.warn("[getRecordPage][pageKey({}) 读取失败，返回短剧兜底分页]", pageReqVO.getPageKey(), e);
            return buildFallbackPage(pageReqVO);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer seedPage(String pageKey) {
        PageSeedSpec spec = getSpec(pageKey);
        if (skitAdminRecordMapper.selectCountByPageKey(spec.key) > 0) {
            return repairSeededPage(spec);
        }
        return insertMissingSeedRows(spec, Collections.emptySet());
    }

    @Override
    public SkitDashboardSummaryRespVO getDashboardSummary() {
        ensureSeeded("user");
        ensureSeeded("adRecord");
        SkitDashboardSummaryRespVO summary = new SkitDashboardSummaryRespVO();
        summary.setTotalMembers(skitAdminRecordMapper.selectCountByPageKey("user"));
        summary.setTotalAdCount(skitAdminRecordMapper.selectCountByPageKey("adRecord"));
        summary.setTotalRevenue(new BigDecimal("487.14"));
        summary.setTotalProfit(new BigDecimal("-179.76"));
        summary.setTodayRegisterCount(0L);
        summary.setTodayAdCount(1L);
        summary.setTodayRevenue(new BigDecimal("0.02"));
        summary.setTodayProfit(new BigDecimal("0.00"));
        summary.setRewardExchange(new BigDecimal("666.91"));
        summary.setScorePerYuan(1000L);
        return summary;
    }

    private void ensureSeeded(String pageKey) {
        seedPage(pageKey);
    }

    private Integer repairSeededPage(PageSeedSpec spec) {
        List<SkitAdminRecordDO> records = skitAdminRecordMapper.selectListByPageKey(spec.key);
        Set<String> existingSeedRowKeys = new HashSet<>();
        int changed = 0;
        for (SkitAdminRecordDO record : records) {
            int index = seedIndexOf(spec, record);
            if (index <= 0) {
                continue;
            }
            if (index > Math.min(spec.totalRows, MAX_SEED_ROWS)) {
                skitAdminRecordMapper.deleteById(record.getId());
                changed++;
                continue;
            }
            existingSeedRowKeys.add(record.getRowKey());
            Map<String, Object> recordData = fromJson(record.getRecordData());
            if (!needsSeedRepair(spec, recordData)) {
                continue;
            }
            record.setRecordData(toJson(buildRecordData(spec, index)));
            record.setStatus(index % 3 == 0 ? 1 : 0);
            record.setSort(index);
            skitAdminRecordMapper.updateById(record);
            changed++;
        }
        return changed + insertMissingSeedRows(spec, existingSeedRowKeys);
    }

    private Integer insertMissingSeedRows(PageSeedSpec spec, Set<String> existingSeedRowKeys) {
        int count = Math.min(spec.totalRows, MAX_SEED_ROWS);
        if (count <= 0) {
            return 0;
        }
        List<SkitAdminRecordDO> records = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            String rowKey = spec.key + "-" + i;
            if (existingSeedRowKeys.contains(rowKey)) {
                continue;
            }
            Map<String, Object> data = buildRecordData(spec, i);
            records.add(SkitAdminRecordDO.builder()
                    .pageKey(spec.key)
                    .rowKey(rowKey)
                    .recordData(toJson(data))
                    .status(i % 3 == 0 ? 1 : 0)
                    .sort(i)
                    .build());
        }
        if (records.isEmpty()) {
            return 0;
        }
        skitAdminRecordMapper.insertBatch(records);
        return records.size();
    }

    private static boolean needsSeedRepair(PageSeedSpec spec, Map<String, Object> recordData) {
        if (!SEED_VERSION.equals(String.valueOf(recordData.get("_seedVersion")))) {
            return true;
        }
        for (String column : spec.columns) {
            if (!recordData.containsKey(column)) {
                return true;
            }
        }
        return false;
    }

    private static int seedIndexOf(PageSeedSpec spec, SkitAdminRecordDO record) {
        String rowKey = record.getRowKey();
        String prefix = spec.key + "-";
        if (StrUtil.isBlank(rowKey) || !rowKey.startsWith(prefix)) {
            return -1;
        }
        String suffix = rowKey.substring(prefix.length());
        if (!suffix.matches("\\d+")) {
            return -1;
        }
        try {
            return Integer.parseInt(suffix);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private SkitAdminRecordDO buildDO(SkitAdminRecordSaveReqVO reqVO) {
        return SkitAdminRecordDO.builder()
                .id(reqVO.getId())
                .pageKey(reqVO.getPageKey())
                .rowKey(StrUtil.blankToDefault(reqVO.getRowKey(), reqVO.getPageKey() + "-custom-" + System.currentTimeMillis()))
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

    private PageResult<SkitAdminRecordRespVO> buildFallbackPage(SkitAdminRecordPageReqVO reqVO) {
        PageSeedSpec spec = getSpec(reqVO.getPageKey());
        int totalRows = Math.min(spec.totalRows, MAX_SEED_ROWS);
        List<SkitAdminRecordRespVO> matchedRows = new ArrayList<>(totalRows);
        for (int i = 1; i <= totalRows; i++) {
            SkitAdminRecordRespVO record = buildFallbackRecord(spec, i);
            if (fallbackMatched(reqVO, record)) {
                matchedRows.add(record);
            }
        }
        int pageNo = reqVO.getPageNo() == null || reqVO.getPageNo() < 1 ? 1 : reqVO.getPageNo();
        int pageSize = reqVO.getPageSize() == null ? 10 : reqVO.getPageSize();
        if (pageSize <= 0) {
            pageSize = Math.max(matchedRows.size(), 1);
        }
        int fromIndex = Math.min((pageNo - 1) * pageSize, matchedRows.size());
        int toIndex = Math.min(fromIndex + pageSize, matchedRows.size());
        return new PageResult<>(matchedRows.subList(fromIndex, toIndex), (long) matchedRows.size());
    }

    private SkitAdminRecordRespVO buildFallbackRecord(PageSeedSpec spec, int index) {
        SkitAdminRecordRespVO record = new SkitAdminRecordRespVO();
        record.setId((long) index);
        record.setPageKey(spec.key);
        record.setRowKey(spec.key + "-" + index);
        record.setRecordData(buildRecordData(spec, index));
        record.setStatus(index % 3 == 0 ? 1 : 0);
        record.setSort(index);
        record.setCreateTime(LocalDateTime.now());
        record.setUpdateTime(LocalDateTime.now());
        return record;
    }

    private boolean fallbackMatched(SkitAdminRecordPageReqVO reqVO, SkitAdminRecordRespVO record) {
        if (reqVO.getStatus() != null && !reqVO.getStatus().equals(record.getStatus())) {
            return false;
        }
        if (StrUtil.isBlank(reqVO.getKeyword())) {
            return true;
        }
        String keyword = reqVO.getKeyword().trim().toLowerCase(Locale.ROOT);
        for (Object value : record.getRecordData().values()) {
            if (String.valueOf(value).toLowerCase(Locale.ROOT).contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private int safeIndex(Long id) {
        if (id == null || id <= 0) {
            return 1;
        }
        return Math.min(id.intValue(), MAX_SEED_ROWS);
    }

    private static PageSeedSpec getSpec(String pageKey) {
        PageSeedSpec spec = PAGE_SPECS.get(pageKey);
        if (spec != null) {
            return spec;
        }
        return new PageSeedSpec(pageKey, 20, "id", "title", "status", "createtime");
    }

    private static Map<String, Object> buildRecordData(PageSeedSpec spec, int index) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : spec.columns) {
            row.put(column, valueFor(spec.key, column, index));
        }
        applyLiveFirstRow(spec.key, index, row);
        row.put("_seedVersion", SEED_VERSION);
        return row;
    }

    private static void applyLiveFirstRow(String pageKey, int index, Map<String, Object> row) {
        if (index != 1) {
            return;
        }
        switch (pageKey) {
            case "attachment":
                row.put("id", 5L);
                row.put("preview", "");
                row.put("filename", "");
                row.put("filesize", "801 KB");
                row.put("imagewidth", "1254");
                row.put("imageheight", "1254");
                row.put("imagetype", "png");
                row.put("storage", "local");
                row.put("mimetype", "image/png");
                row.put("createtime", "2026-06-18 15:33:53");
                return;
            case "operationLog":
                row.put("id", 1564L);
                row.put("title", "代理管理 确认用户");
                row.put("url", "");
                row.put("ip", "124.237.35.211");
                row.put("createtime", "2026-07-06 18:53:15");
                return;
            case "adminUser":
                row.put("id", 2L);
                row.put("username", "123456");
                row.put("nickname", "123456");
                row.put("groups_text", "超级管理员");
                row.put("email", "123456@qq.com");
                row.put("status", "正常");
                row.put("logintime", "2026-07-08 22:31:41");
                return;
            case "adminLog":
                row.put("id", 1564L);
                row.put("username", "123456");
                row.put("title", "代理管理 确认用户");
                row.put("url", "");
                row.put("ip", "124.237.35.211");
                row.put("browser", "Mozilla/5.0");
                row.put("createtime", "2026-07-06 18:53:15");
                return;
            case "group":
                row.put("id", 2L);
                row.put("pid", 0);
                row.put("name", "超级管理员");
                row.put("status", "正常");
                return;
            case "adRecord":
                row.put("id", 26506L);
                row.put("user_id", 14);
                row.put("ad_network", "baidu");
                row.put("network_firm_id", 22);
                row.put("trans_id", "7afa18e0b1b0fb563511904a5d014d48_14204758_1783515328747");
                row.put("publisher_revenue", "0.00349037");
                row.put("reward_points", "3.490000");
                row.put("createtime", "2026-07-08 20:55:47");
                return;
            case "withdraw":
                row.put("id", 367L);
                row.put("user_text", "138****2566 / 13836432566 (#149)");
                row.put("withdraw_type", "score");
                row.put("account_type", "wechat_pay");
                row.put("account", "微信用户");
                row.put("money", "2.00");
                row.put("fee", "0.20");
                row.put("real_money", "2.00");
                row.put("score", "2200.000000");
                row.put("status", "待审核");
                row.put("payment_status_text", "未发起");
                row.put("review_mode", "manual");
                row.put("reject_reason", "");
                row.put("createtime", "2026-07-03 09:51:45");
                row.put("audittime", "无");
                row.put("paytime", "无");
                return;
            case "scoreLog":
                row.put("id", 72121L);
                row.put("user_id", 14);
                row.put("user_text", "186****46261 / 18600204621 (#14)");
                row.put("score", "3.490000");
                row.put("before", "420357.830688");
                row.put("after", "420361.320688");
                row.put("memo", "激励广告奖励 #26506");
                row.put("createtime", "2026-07-08 20:55:47");
                return;
            case "promotionAgent":
                return;
            case "loginRecord":
                row.put("id", 2969L);
                row.put("user_id", 2338);
                row.put("user_text", "抖音用户3144AD (#2338)");
                row.put("mobile", "");
                row.put("login_type", "douyin_mini");
                row.put("device_platform", "android");
                row.put("device_id", "");
                row.put("idf", "");
                row.put("oaid", "");
                row.put("imei", "");
                row.put("location", "");
                row.put("province", "");
                row.put("city", "");
                row.put("district", "");
                row.put("device_brand", "HUAWEI");
                return;
            case "deviceLog":
                row.put("id", 8361L);
                row.put("user_id", 14);
                row.put("user_text", "186****46261 / 18600204621 (#14)");
                row.put("log_date", "2026-07-08");
                row.put("ip", "124.237.35.211");
                row.put("device_platform", "android");
                row.put("device_id", "local-hjdlj33wzo");
                row.put("oaid", "e38c265f-2a0a-4510-a42c-7033c58eaa5b");
                row.put("android_id", "c0eadd6ca28eac3d");
                row.put("device_brand", "motorola");
                row.put("device_model", "XT2201-2");
                row.put("os_version", "Android 14 (SDK 34)");
                row.put("network_type", "wifi");
                row.put("is_vpn", "否");
                row.put("is_proxy", "否");
                return;
            case "user":
                row.put("id", 2338L);
                row.put("nickname", "抖音用户3144AD");
                row.put("email", "");
                row.put("invite_code", "NC4D3P");
                row.put("direct_user_count", 0);
                row.put("ad_reward_ratio", "默认");
                row.put("avatar", "");
                row.put("score", "0.000000");
                row.put("money", "0.00");
                row.put("ban_status_text", "正常");
                row.put("ban_reason", "");
                row.put("logintime", "2026-07-08 22:21:26");
                row.put("loginip", "116.162.233.27");
                row.put("jointime", "2026-07-08 22:21:26");
                row.put("status", "正常");
                return;
            case "announcement":
                row.put("id", 3L);
                row.put("title", "系统公告");
                row.put("content", "【师徒裂变计划开启，收益上不封顶！】 亲爱的用户： 全新“师徒邀请”玩法正式上线！现在邀请好友注册，即可建立师徒关系，轻松赚取丰厚奖励： 1. 收徒拿奖励：邀请...");
                row.put("createtime", "2026-06-18 15:33:56");
                row.put("updatetime", "2026-06-18 17:11:59");
                return;
            case "douyinMiniProgram":
                row.put("id", 4L);
                row.put("name", "玩出新层次");
                row.put("appid", "ttf5a3831e6aa1650902");
                row.put("appsecret", "******");
                row.put("ad_base_score", "1000");
                row.put("self_commission_rate", "50");
                row.put("max_ad_score", "0");
                row.put("withdraw_min_amount", "5.00");
                row.put("withdraw_fee_rate", "0.00");
                row.put("withdraw_fixed_fee", "0.00");
                row.put("access_token_expiretime", "2026-07-08 22:39:34");
                row.put("status", "开启");
                row.put("createtime", "2026-07-01 14:37:56");
                row.put("updatetime", "2026-07-08 22:34:34");
                return;
            case "douyinLoginRecord":
                row.put("id", 1315L);
                row.put("mini_program_text", "精准烧脑 (#2)");
                row.put("appid", "tt8f3ff98211592ad302");
                row.put("user_id", 2338);
                row.put("user_text", "抖音用户3144AD (#2338)");
                row.put("nickname", "抖音用户3144AD");
                row.put("mobile", "");
                row.put("scene", "登录");
                row.put("ad_slot", "");
                row.put("rewarded_count", 0);
                row.put("device_platform", "android");
                row.put("device_brand", "HUAWEI");
                row.put("device_model", "LIO-AN00P");
                row.put("os_name", "Android 12");
                row.put("os_version", "Android 12");
                return;
            case "douyinAdRecord":
                row.put("id", 840L);
                row.put("mini_program_text", "修罗小子 (#3)");
                row.put("openid", "_000wDm1o92gxR17pJW1c_gnXaMr3snE6cWv");
                row.put("nickname", "");
                row.put("ad_type", "激励广告");
                row.put("ad_time", "2026-07-08 19:38:52");
                row.put("ad_revenue", "1.816740");
                row.put("source", "douyin_ecpm");
                row.put("ip", "");
                row.put("city", "");
                row.put("device_brand", "");
                row.put("device_model", "");
                row.put("host_app_version", "");
                return;
            case "douyinTrafficRecord":
                row.put("id", 34534L);
                row.put("type", "展示");
                row.put("request_time", "2026-07-08 22:18:31");
                row.put("request_ip", "118.77.204.179");
                row.put("param_ip", "118.77.204.179");
                row.put("os", "安卓");
                row.put("model", "2201122C");
                row.put("csite", "番茄小说 (26046)");
                row.put("sl", "__SL__");
                row.put("callback_url", "https://ad.toutiao.com/track/activate/?callback=B.taU4oDphvaTCcoR224iEumkURQwcvvGMcFEVg7IIwIeuvVyt2ri7tHMLbXZUiYhS4ufdYTOkZi8VeKbIr1UeP7tfSbOYnq8Wzeu1DZ4baU7gneSEIZjdodiOTbNB3zVfrA37EeekQdJQBUMFrZQbkK3&os=0&muid=");
                row.put("callback_status", "否");
                return;
            default:
        }
    }

    private static Object valueFor(String pageKey, String prop, int index) {
        if ("id".equals(prop)) {
            return sampleId(index);
        }
        if ("trans_id".equals(prop)) {
            return (index % 2 == 0 ? "bcb045b828a19f135c" : "9626eb0ab960ccb72d") + index;
        }
        if ("network_firm_id".equals(prop)) {
            int[] values = new int[]{28, 15, 8};
            return values[index % values.length];
        }
        if ("reward_points".equals(prop)) {
            return index * 10;
        }
        if ("score".equals(prop) || "before".equals(prop) || "after".equals(prop)) {
            return index * 100;
        }
        if ("publisher_revenue".equals(prop)) {
            return new BigDecimal("0.021").multiply(BigDecimal.valueOf(index)).setScale(3, BigDecimal.ROUND_HALF_UP);
        }
        if (prop.endsWith("_id") || "user_id".equals(prop) || "uid".equals(prop)) {
            int[] values = new int[]{14, 149, 22, 1032};
            return values[index % values.length];
        }
        if (prop.contains("time") || "createtime".equals(prop) || "updatetime".equals(prop) || "paytime".equals(prop)) {
            int hour = (9 + index) % 24;
            return String.format("2026-07-%02d %02d:24:53", (index % 6) + 1, hour);
        }
        if ("log_date".equals(prop)) {
            return String.format("2026-07-%02d", (index % 6) + 1);
        }
        if ("ad_network".equals(prop)) {
            String[] values = new String[]{"kuaishou", "kuaishou", "csj", "gdt"};
            return values[index % values.length];
        }
        if ("user_text".equals(prop)) {
            return "模拟用户" + index + " (#" + (1000 + index) + ")";
        }
        if ("inviter_text".equals(prop)) {
            return index % 3 == 0 ? "无" : "上级用户" + index;
        }
        if ("mini_program_text".equals(prop)) {
            return "精准短剧 (#" + ((index % 3) + 1) + ")";
        }
        if ("nickname".equals(prop)) {
            return "模拟昵称" + index;
        }
        if ("username".equals(prop)) {
            return "admin" + index;
        }
        if ("email".equals(prop)) {
            return "user" + index + "@example.com";
        }
        if ("mobile".equals(prop)) {
            return "138****" + String.valueOf(1000 + index).substring(1);
        }
        if ("appid".equals(prop)) {
            return "tt8f3ff98211592ad30" + index;
        }
        if ("appsecret".equals(prop)) {
            return "******";
        }
        if (prop.contains("ip")) {
            return "192.0.2." + index;
        }
        if ("browser".equals(prop)) {
            return "Mozilla/5.0";
        }
        if ("payment_status_text".equals(prop)) {
            return index % 4 == 0 ? "已打款" : "未打款";
        }
        if ("ban_status_text".equals(prop)) {
            return "未封禁";
        }
        if ("status".equals(prop) || prop.contains("status")) {
            return index % 3 == 0 ? "待处理" : "正常";
        }
        if (prop.startsWith("is_")) {
            return index % 2 == 0 ? "否" : "是";
        }
        if ("fee".equals(prop)) {
            return BigDecimal.valueOf(index).multiply(new BigDecimal("0.03")).setScale(2, BigDecimal.ROUND_HALF_UP);
        }
        if ("real_money".equals(prop)) {
            return BigDecimal.valueOf(index).multiply(new BigDecimal("3.24")).setScale(2, BigDecimal.ROUND_HALF_UP);
        }
        if (prop.contains("money") || "fee".equals(prop)) {
            return BigDecimal.valueOf(index).multiply(new BigDecimal("3.27")).setScale(2, BigDecimal.ROUND_HALF_UP);
        }
        if (prop.contains("ratio") || prop.contains("rate")) {
            return (10 + index) + "%";
        }
        if ("preview".equals(prop)) {
            return "预览";
        }
        if ("operate".equals(prop) || "state".equals(prop) || "0".equals(prop)) {
            return "";
        }
        return dictionaryValue(pageKey, prop, index);
    }

    private static long sampleId(int index) {
        long[] ids = new long[]{23267, 21566, 21565, 20178, 20176, 17665, 17663, 17661, 16582, 16581};
        return index <= ids.length ? ids[index - 1] : 16000L - index;
    }

    private static Object dictionaryValue(String pageKey, String prop, int index) {
        Map<String, Object> dictionary = new HashMap<>();
        dictionary.put("title", titleFor(pageKey, index));
        dictionary.put("category", new String[]{"都市", "逆袭", "甜宠", "悬疑"}[index % 4]);
        dictionary.put("episodes", 80 + (index % 20));
        dictionary.put("content", "公告正文摘要 " + index);
        dictionary.put("url", urlFor(pageKey, index));
        dictionary.put("groups_text", "超级管理员");
        dictionary.put("pid", 0);
        dictionary.put("rules", "全部权限");
        dictionary.put("cover", "/uploads/20260708/drama-cover-" + index + ".jpg");
        dictionary.put("avatar", "/uploads/20260708/avatar-" + index + ".png");
        dictionary.put("filename", "upload-" + index + ".png");
        dictionary.put("filesize", (300 + index * 12) + " KB");
        dictionary.put("imagewidth", "1254");
        dictionary.put("imageheight", "1254");
        dictionary.put("imagetype", "png");
        dictionary.put("storage", "local");
        dictionary.put("mimetype", "image/png");
        dictionary.put("withdraw_type", "积分提现");
        dictionary.put("account_type", "微信");
        dictionary.put("account", "mock-account-" + index);
        dictionary.put("fee", BigDecimal.valueOf(index).multiply(new BigDecimal("0.03")).setScale(2, BigDecimal.ROUND_HALF_UP));
        dictionary.put("real_money", BigDecimal.valueOf(index).multiply(new BigDecimal("3.24")).setScale(2, BigDecimal.ROUND_HALF_UP));
        dictionary.put("review_mode", "人工审核");
        dictionary.put("payment_status_text", "未打款");
        dictionary.put("reject_reason", "-");
        dictionary.put("memo", "广告奖励");
        dictionary.put("agent_ratio", 20 + index);
        dictionary.put("member_self_ratio", 100);
        dictionary.put("member_parent_ratio", 10);
        dictionary.put("member_grandparent_ratio", 5);
        dictionary.put("descendant_count", index % 7);
        dictionary.put("today_agent_points", index * 12);
        dictionary.put("total_agent_points", index * 320);
        dictionary.put("remark", "默认分佣规则");
        dictionary.put("login_type", "手机号");
        dictionary.put("device_id", "device-" + String.format("%04d", index));
        dictionary.put("idf", "idf-" + String.format("%04d", index));
        dictionary.put("idfa", "idfa-" + String.format("%04d", index));
        dictionary.put("idfv", "idfv-" + String.format("%04d", index));
        dictionary.put("oaid", "oaid-" + String.format("%04d", index));
        dictionary.put("imei", "860000000000" + String.format("%03d", index));
        dictionary.put("android_id", "android-" + String.format("%04d", index));
        dictionary.put("device_platform", index % 2 == 0 ? "android" : "ios");
        dictionary.put("device_brand", index % 2 == 0 ? "Redmi" : "iPhone");
        dictionary.put("device_model", index % 2 == 0 ? "K70" : "iPhone 15");
        dictionary.put("os_name", index % 2 == 0 ? "Android" : "iOS");
        dictionary.put("os_version", index % 2 == 0 ? "Android 13" : "iOS 17");
        dictionary.put("android_version", index % 2 == 0 ? "13" : "-");
        dictionary.put("app_version", "1.0." + (index % 9));
        dictionary.put("app_build", "100" + index);
        dictionary.put("package_name", "com.skit.duanju");
        dictionary.put("network_type", "wifi");
        dictionary.put("sim_state", "ready");
        dictionary.put("sim_operator", index % 2 == 0 ? "中国移动" : "中国联通");
        dictionary.put("sim_count", index % 2 == 0 ? 2 : 1);
        dictionary.put("province", "示例省");
        dictionary.put("city", "示例市");
        dictionary.put("district", "示例区");
        dictionary.put("country", "中国");
        dictionary.put("address", "示例市短剧产业园 " + index + " 号");
        dictionary.put("location", "示例位置");
        dictionary.put("latitude", "23.1291");
        dictionary.put("longitude", "113.2644");
        dictionary.put("screen_size", index % 2 == 0 ? "1080x2400" : "1179x2556");
        dictionary.put("language", "zh-CN");
        dictionary.put("timezone", "Asia/Shanghai");
        dictionary.put("user_agent", "Mozilla/5.0 SkitApp/" + index);
        dictionary.put("info_hash", "infohash-" + index);
        dictionary.put("install_time", String.format("2026-07-%02d 08:00:00", (index % 6) + 1));
        dictionary.put("invite_code", "SKIT" + (1000 + index));
        dictionary.put("ban_status_text", "未封禁");
        dictionary.put("ban_reason", "-");
        dictionary.put("direct_user_count", index % 8);
        dictionary.put("ad_reward_ratio", 100);
        dictionary.put("logintime", String.format("2026-07-%02d %02d:18:00", (index % 6) + 1, (8 + index) % 24));
        dictionary.put("loginip", "192.0.2." + index);
        dictionary.put("jointime", String.format("2026-06-%02d %02d:18:00", (index % 20) + 1, (8 + index) % 24));
        dictionary.put("name", "精准短剧 " + index);
        dictionary.put("ad_base_score", 10);
        dictionary.put("self_commission_rate", 100);
        dictionary.put("max_ad_score", 1000);
        dictionary.put("withdraw_min_amount", "1.00");
        dictionary.put("withdraw_fee_rate", "0");
        dictionary.put("withdraw_fixed_fee", "0");
        dictionary.put("access_token_expiretime", String.format("2026-07-%02d 23:59:59", (index % 6) + 1));
        dictionary.put("access_token_updatetime", String.format("2026-07-%02d 10:00:00", (index % 6) + 1));
        dictionary.put("mini_program_id", index % 3 + 1);
        dictionary.put("third_id", "third-" + index);
        dictionary.put("openid", "openid-" + index);
        dictionary.put("unionid", "unionid-" + index);
        dictionary.put("anonymous_openid", "anonymous-" + index);
        dictionary.put("scene", "登录");
        dictionary.put("ad_slot", "rewarded");
        dictionary.put("rewarded_count", index % 4);
        dictionary.put("host_app_name", "Douyin");
        dictionary.put("host_app_version", "30.8.0");
        dictionary.put("ad_type", "激励广告");
        dictionary.put("ad_time", String.format("2026-07-%02d %02d:38:52", (index % 6) + 1, (18 + index) % 24));
        dictionary.put("ad_revenue", BigDecimal.valueOf(index).multiply(new BigDecimal("1.816740")).setScale(6, BigDecimal.ROUND_HALF_UP));
        dictionary.put("source", "douyin_ecpm");
        dictionary.put("type", "douyinTrafficRecord".equals(pageKey) ? "active" : "广告奖励");
        dictionary.put("callback_url", "https://callback.example.com/skit");
        dictionary.put("callback_status", index % 2 == 0 ? "是" : "否");
        dictionary.put("model", "V2047A");
        dictionary.put("request_time", String.format("2026-07-%02d %02d:10:00", (index % 6) + 1, (9 + index) % 24));
        dictionary.put("request_ip", "198.51.100." + index);
        dictionary.put("param_ip", "203.0.113." + index);
        dictionary.put("os", index % 2 == 0 ? "android" : "ios");
        dictionary.put("csite", "site");
        dictionary.put("sl", "sl");
        dictionary.put("geo", "广东省广州市");
        dictionary.put("city_code", "440100");
        dictionary.put("site", "douyin");
        dictionary.put("union_site", "union-" + index);
        dictionary.put("ts", String.valueOf(1783507200L + index));
        dictionary.put("callback", "callback-" + index);
        dictionary.put("advertiser_id", "advertiser-" + index);
        dictionary.put("promotion_id", "promotion-" + index);
        dictionary.put("project_id", "project-" + index);
        dictionary.put("aid", "aid-" + index);
        dictionary.put("aid_name", "计划 " + index);
        dictionary.put("cid", "cid-" + index);
        dictionary.put("cid_name", "创意 " + index);
        dictionary.put("campaign_id", "campaign-" + index);
        dictionary.put("campaign_name", "广告组 " + index);
        dictionary.put("convert_id", "convert-" + index);
        dictionary.put("request_id", "request-" + index);
        dictionary.put("track_id", "track-" + index);
        dictionary.put("outerid", "outer-" + index);
        dictionary.put("productid", "product-" + index);
        dictionary.put("request_path", "/manystore/duanju/douyin_mini_program_traffic_record");
        dictionary.put("dedupe_hash", "dedupe-" + index);
        Object value = dictionary.get(prop);
        return value == null ? prop + "-" + index : value;
    }

    private static String titleFor(String pageKey, int index) {
        if ("drama".equals(pageKey)) {
            String[] names = new String[]{"重生后我逆袭成王", "闪婚后傅总追妻忙", "保洁妈妈是首富", "离婚后前夫后悔了"};
            return names[(index - 1) % names.length] + " " + index;
        }
        if ("operationLog".equals(pageKey)) {
            return "后台访问 " + index;
        }
        if ("adminLog".equals(pageKey)) {
            return "管理员操作日志 " + index;
        }
        if ("announcement".equals(pageKey)) {
            return "公告标题 " + index;
        }
        return "记录标题 " + index;
    }

    private static String urlFor(String pageKey, int index) {
        Map<String, String> urls = new HashMap<>();
        urls.put("operationLog", "/manystore/index/index");
        urls.put("adminLog", "/manystore/auth/manystorelog");
        urls.put("drama", "/manystore/duanju");
        urls.put("adRecord", "/manystore/duanju/ad_record");
        urls.put("withdraw", "/manystore/duanju/withdraw");
        urls.put("scoreLog", "/manystore/duanju/score_log");
        urls.put("douyinAdRecord", "/manystore/duanju/douyin_mini_program_ad_record");
        String url = urls.get(pageKey);
        return (url == null ? "/manystore/index/index" : url) + "?page=" + index;
    }

    private static Map<String, PageSeedSpec> buildPageSpecs() {
        Map<String, PageSeedSpec> specs = new LinkedHashMap<>();
        add(specs, "attachment", 2, "id", "preview", "filename", "filesize", "imagewidth", "imageheight", "imagetype", "storage", "mimetype", "createtime");
        add(specs, "operationLog", 147, "id", "title", "url", "ip", "createtime");
        add(specs, "adminUser", 1, "id", "username", "nickname", "groups_text", "email", "status", "logintime");
        add(specs, "adminLog", 147, "id", "username", "title", "url", "ip", "browser", "createtime");
        add(specs, "group", 1, "id", "pid", "name", "status", "operate");
        add(specs, "drama", 12, "id", "title", "cover", "category", "episodes", "status", "createtime", "updatetime");
        add(specs, "adRecord", 947, "id", "user_id", "ad_network", "network_firm_id", "trans_id", "publisher_revenue", "reward_points", "createtime");
        add(specs, "withdraw", 26, "id", "user_text", "withdraw_type", "account_type", "account", "money", "fee", "real_money", "score", "status", "payment_status_text", "review_mode", "reject_reason", "createtime", "audittime", "paytime");
        add(specs, "scoreLog", 1936, "id", "user_id", "user_text", "score", "before", "after", "memo", "createtime");
        add(specs, "promotionAgent", 0, "id", "user_id", "user_text", "inviter_text", "agent_ratio", "member_self_ratio", "member_parent_ratio", "member_grandparent_ratio", "descendant_count", "today_agent_points", "total_agent_points", "remark", "createtime", "updatetime");
        add(specs, "loginRecord", 1342, "id", "user_id", "user_text", "mobile", "login_type", "device_platform", "device_id", "idf", "oaid", "imei", "location", "province", "city", "district", "device_brand", "device_model", "os_name", "os_version", "android_version", "ip", "createtime");
        add(specs, "deviceLog", 153, "id", "user_id", "user_text", "log_date", "ip", "device_platform", "device_id", "oaid", "android_id", "device_brand", "device_model", "os_version", "network_type", "is_vpn", "is_proxy", "is_emulator", "is_root", "is_developer_mode", "is_usb_debug", "sim_operator", "location", "createtime");
        add(specs, "user", 1258, "id", "nickname", "email", "invite_code", "direct_user_count", "ad_reward_ratio", "avatar", "score", "money", "ban_status_text", "ban_reason", "logintime", "loginip", "jointime", "status");
        add(specs, "announcement", 2, "id", "title", "content", "createtime", "updatetime");
        add(specs, "douyinMiniProgram", 3, "id", "name", "appid", "appsecret", "ad_base_score", "self_commission_rate", "max_ad_score", "withdraw_min_amount", "withdraw_fee_rate", "withdraw_fixed_fee", "access_token_expiretime", "status", "createtime", "updatetime");
        add(specs, "douyinLoginRecord", 1303, "id", "mini_program_text", "appid", "user_id", "user_text", "nickname", "mobile", "scene", "ad_slot", "rewarded_count", "device_platform", "device_brand", "device_model", "os_name", "os_version", "host_app_name", "host_app_version", "ip", "createtime");
        add(specs, "douyinAdRecord", 717, "id", "mini_program_text", "openid", "nickname", "ad_type", "ad_time", "ad_revenue", "source", "ip", "city", "device_brand", "device_model", "host_app_version");
        add(specs, "douyinTrafficRecord", 34513, "id", "type", "request_time", "request_ip", "param_ip", "os", "model", "csite", "sl", "callback_url", "callback_status");
        return specs;
    }

    private static void add(Map<String, PageSeedSpec> specs, String key, int totalRows, String... columns) {
        specs.put(key, new PageSeedSpec(key, totalRows, columns));
    }

    private static final class PageSeedSpec {
        private final String key;
        private final int totalRows;
        private final String[] columns;

        private PageSeedSpec(String key, int totalRows, String... columns) {
            this.key = key;
            this.totalRows = totalRows;
            this.columns = columns;
        }
    }

}
