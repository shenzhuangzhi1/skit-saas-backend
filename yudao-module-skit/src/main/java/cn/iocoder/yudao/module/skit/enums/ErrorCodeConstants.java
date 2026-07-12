package cn.iocoder.yudao.module.skit.enums;

import cn.iocoder.yudao.framework.common.exception.ErrorCode;

/**
 * 短剧 SaaS 错误码，使用 1-030 段。
 */
public interface ErrorCodeConstants {

    ErrorCode PLATFORM_ADMIN_REQUIRED = new ErrorCode(1_030_001_000, "仅平台超级管理员可执行该操作");
    ErrorCode AGENT_NOT_EXISTS = new ErrorCode(1_030_001_001, "代理商不存在");
    ErrorCode AGENT_CODE_EXISTS = new ErrorCode(1_030_001_002, "代理商编码已存在");

    ErrorCode AD_ACCOUNT_NOT_EXISTS = new ErrorCode(1_030_002_000, "广告账号不存在");
    ErrorCode AD_PROVIDER_INVALID = new ErrorCode(1_030_002_001, "广告平台仅支持 PANGLE 或 TAKU");
    ErrorCode AD_ACCOUNT_CONFIG_INVALID = new ErrorCode(1_030_002_002, "广告账号配置不完整：{}");

    ErrorCode INVITE_CODE_INVALID = new ErrorCode(1_030_003_000, "邀请码不存在或已失效");
    ErrorCode MEMBER_MOBILE_EXISTS = new ErrorCode(1_030_003_001, "该手机号已绑定其他账号");
    ErrorCode MEMBER_LOGIN_FAILED = new ErrorCode(1_030_003_002, "手机号或密码不正确");
    ErrorCode MEMBER_DISABLED = new ErrorCode(1_030_003_003, "会员账号已停用");
    ErrorCode MEMBER_NOT_EXISTS = new ErrorCode(1_030_003_004, "会员不存在");
    ErrorCode MEMBER_TOKEN_SCOPE_INVALID = new ErrorCode(1_030_003_006, "会员令牌不属于短剧会员认证域");

    ErrorCode COMMISSION_RULE_INVALID = new ErrorCode(1_030_004_000, "分成规则不合法：{}");
    ErrorCode COMMISSION_PLAN_NOT_EXISTS = new ErrorCode(1_030_004_001, "当前租户尚未配置分成规则");

    ErrorCode REVENUE_EVENT_INVALID = new ErrorCode(1_030_005_000, "广告收益事件不合法：{}");
    ErrorCode REVENUE_EVENT_CONFLICT = new ErrorCode(1_030_005_001, "externalEventId 已被不同的广告事件占用");

}
