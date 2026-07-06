package cn.iocoder.yudao.module.skit.service.record;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
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
import java.time.LocalDateTime;
import java.util.*;

/**
 * 短剧 SaaS 通用记录 Service 实现。
 */
@Service
@Validated
public class SkitAdminRecordServiceImpl implements SkitAdminRecordService {

    private static final Map<String, PageSeedSpec> PAGE_SPECS = buildPageSpecs();
    private static final int MAX_SEED_ROWS = 2000;

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
    @Transactional(rollbackFor = Exception.class)
    public PageResult<SkitAdminRecordRespVO> getRecordPage(SkitAdminRecordPageReqVO pageReqVO) {
        ensureSeeded(pageReqVO.getPageKey());
        PageResult<SkitAdminRecordDO> pageResult = skitAdminRecordMapper.selectPage(pageReqVO);
        List<SkitAdminRecordRespVO> list = new ArrayList<>(pageResult.getList().size());
        for (SkitAdminRecordDO record : pageResult.getList()) {
            list.add(convert(record));
        }
        return new PageResult<>(list, pageResult.getTotal());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Integer seedPage(String pageKey) {
        PageSeedSpec spec = getSpec(pageKey);
        if (skitAdminRecordMapper.selectCountByPageKey(spec.key) > 0) {
            return 0;
        }
        int count = Math.min(spec.totalRows, MAX_SEED_ROWS);
        if (count <= 0) {
            return 0;
        }
        List<SkitAdminRecordDO> records = new ArrayList<>(count);
        for (int i = 1; i <= count; i++) {
            Map<String, Object> data = buildRecordData(spec, i);
            records.add(SkitAdminRecordDO.builder()
                    .pageKey(spec.key)
                    .rowKey(spec.key + "-" + i)
                    .recordData(toJson(data))
                    .status(i % 3 == 0 ? 1 : 0)
                    .sort(i)
                    .build());
        }
        skitAdminRecordMapper.insertBatch(records);
        return records.size();
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
        if (skitAdminRecordMapper.selectCountByPageKey(pageKey) == 0) {
            seedPage(pageKey);
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
        SkitAdminRecordRespVO respVO = BeanUtils.toBean(record, SkitAdminRecordRespVO.class);
        fillRecordData(record, respVO);
        return respVO;
    }

    private void fillRecordData(SkitAdminRecordDO record, SkitAdminRecordRespVO respVO) {
        respVO.setRecordData(fromJson(record.getRecordData()));
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
            throw new IllegalArgumentException("短剧记录 JSON 解析失败", e);
        }
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
            row.put(column, valueFor(column, index));
        }
        return row;
    }

    private static Object valueFor(String prop, int index) {
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
        if ("reward_points".equals(prop) || "score".equals(prop) || "before".equals(prop) || "after".equals(prop)) {
            return index * 10;
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
        if ("status".equals(prop) || prop.contains("status")) {
            return index % 3 == 0 ? "待处理" : "正常";
        }
        if (prop.startsWith("is_")) {
            return index % 2 == 0 ? "否" : "是";
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
        return dictionaryValue(prop, index);
    }

    private static long sampleId(int index) {
        long[] ids = new long[]{23267, 21566, 21565, 20178, 20176, 17665, 17663, 17661, 16582, 16581};
        return index <= ids.length ? ids[index - 1] : 16000L - index;
    }

    private static Object dictionaryValue(String prop, int index) {
        Map<String, Object> dictionary = new HashMap<>();
        dictionary.put("title", "公告标题 " + index);
        dictionary.put("content", "公告正文摘要 " + index);
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
        dictionary.put("review_mode", "人工审核");
        dictionary.put("payment_status_text", "未打款");
        dictionary.put("reject_reason", "-");
        dictionary.put("memo", "广告奖励");
        dictionary.put("login_type", "手机号");
        dictionary.put("device_platform", index % 2 == 0 ? "android" : "ios");
        dictionary.put("device_brand", index % 2 == 0 ? "Redmi" : "iPhone");
        dictionary.put("device_model", index % 2 == 0 ? "K70" : "iPhone 15");
        dictionary.put("os_name", index % 2 == 0 ? "Android" : "iOS");
        dictionary.put("os_version", index % 2 == 0 ? "Android 13" : "iOS 17");
        dictionary.put("network_type", "wifi");
        dictionary.put("province", "示例省");
        dictionary.put("city", "示例市");
        dictionary.put("district", "示例区");
        dictionary.put("location", "示例位置");
        dictionary.put("invite_code", "SKIT" + (1000 + index));
        dictionary.put("ban_status_text", "未封禁");
        dictionary.put("name", "精准短剧 " + index);
        dictionary.put("scene", "登录");
        dictionary.put("ad_slot", "rewarded");
        dictionary.put("rewarded_count", index % 4);
        dictionary.put("host_app_name", "Douyin");
        dictionary.put("host_app_version", "30.8.0");
        dictionary.put("type", "active");
        dictionary.put("callback_url", "https://callback.example.com/skit");
        dictionary.put("model", "V2047A");
        dictionary.put("csite", "site");
        dictionary.put("sl", "sl");
        Object value = dictionary.get(prop);
        return value == null ? prop + "-" + index : value;
    }

    private static Map<String, PageSeedSpec> buildPageSpecs() {
        Map<String, PageSeedSpec> specs = new LinkedHashMap<>();
        add(specs, "attachment", 2, "id", "preview", "filename", "filesize", "imagewidth", "imageheight", "imagetype", "storage", "mimetype", "createtime");
        add(specs, "operationLog", 147, "id", "title", "url", "ip", "createtime");
        add(specs, "adminUser", 1, "id", "username", "nickname", "groups_text", "email", "status", "logintime");
        add(specs, "adminLog", 147, "id", "username", "title", "url", "ip", "browser", "createtime");
        add(specs, "group", 3, "id", "name", "rules", "createtime");
        add(specs, "drama", 12, "id", "title", "cover", "status", "createtime");
        add(specs, "adRecord", 943, "id", "user_id", "ad_network", "network_firm_id", "trans_id", "publisher_revenue", "reward_points", "createtime");
        add(specs, "withdraw", 18, "id", "user_id", "user_text", "withdraw_type", "account_type", "account", "money", "score", "review_mode", "payment_status_text", "reject_reason", "createtime", "paytime");
        add(specs, "scoreLog", 666, "id", "user_id", "type", "score", "before", "after", "memo", "createtime");
        add(specs, "promotionAgent", 63, "id", "user_id", "user_text", "inviter_text", "invite_code", "ratio", "status", "createtime");
        add(specs, "loginRecord", 182, "id", "user_id", "login_type", "ip", "province", "city", "createtime");
        add(specs, "deviceLog", 121, "id", "user_id", "device_platform", "device_brand", "device_model", "os_name", "os_version", "network_type", "createtime");
        add(specs, "user", 63, "id", "nickname", "mobile", "invite_code", "score", "ban_status_text", "createtime");
        add(specs, "announcement", 5, "id", "title", "content", "status", "createtime");
        add(specs, "douyinMiniProgram", 3, "id", "name", "appid", "appsecret", "callback_url", "status", "createtime");
        add(specs, "douyinLoginRecord", 22, "id", "user_id", "mini_program_text", "host_app_name", "host_app_version", "model", "ip", "createtime");
        add(specs, "douyinAdRecord", 0, "id", "user_id", "ad_slot", "status", "createtime");
        add(specs, "douyinTrafficRecord", 9, "id", "mini_program_text", "csite", "sl", "type", "createtime");
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
