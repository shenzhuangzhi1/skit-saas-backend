package cn.iocoder.yudao.module.skit.enums;

/**
 * 短剧 SaaS 强类型领域常量。
 */
public final class SkitDomainConstants {

    public static final String PROVIDER_PANGLE = "PANGLE";
    public static final String PROVIDER_TAKU = "TAKU";

    public static final int COMMISSION_PLAN_ACTIVE = 0;
    public static final int COMMISSION_PLAN_ARCHIVED = 1;

    public static final int REVENUE_EVENT_IGNORED = 0;
    /** 客户端 eCPM 上报只能形成预估收益，待服务端报表/S2S 对账后才能结算。 */
    public static final int REVENUE_EVENT_ESTIMATED = 1;

    public static final String REVENUE_SOURCE_LEGACY_CLIENT = "LEGACY_CLIENT";
    public static final String REVENUE_MATCH_LEGACY_UNMATCHED = "LEGACY_UNMATCHED";
    public static final String REVENUE_VERIFICATION_LEGACY_UNVERIFIED = "LEGACY_UNVERIFIED";
    public static final String REWARD_QUALIFICATION_NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String REVENUE_RECONCILIATION_NON_SETTLEABLE = "NON_SETTLEABLE";

    public static final int LEDGER_ESTIMATED = 0;
    public static final int LEDGER_AVAILABLE = 1;
    public static final String LEDGER_ENTRY_LEGACY_ESTIMATE = "LEGACY_ESTIMATE";
    public static final String LEDGER_BALANCE_NON_SETTLEABLE = "NON_SETTLEABLE";
    public static final String LEGACY_CURRENCY_CNY = "CNY";
    public static final int BENEFICIARY_MEMBER = 1;
    public static final int BENEFICIARY_AGENT = 2;

    public static final int AGENT_LEDGER_LEVEL = -1;
    public static final long AGENT_BENEFICIARY_ID = 0L;
    public static final int RATE_BASE = 10_000;
    public static final int MONEY_SCALE = 8;

    private SkitDomainConstants() {
    }

    public static boolean isSupportedProvider(String provider) {
        return PROVIDER_PANGLE.equalsIgnoreCase(provider) || PROVIDER_TAKU.equalsIgnoreCase(provider);
    }

}
