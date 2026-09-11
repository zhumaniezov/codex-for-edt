package io.github.zhumaniezov.codex.edt.settings;

/** Закрытый список RPC настроек; произвольные команды и инструменты сюда не передаются. */
public enum ManagementRequest {
    CONFIG_READ("config/read"), CONFIG_WRITE("config/value/write"), CONFIG_BATCH("config/batchWrite"),
    MCP_LIST("mcpServerStatus/list"), MCP_RELOAD("config/mcpServer/reload"), MCP_LOGIN("mcpServer/oauth/login"),
    ACCOUNT_READ("account/read"), LIMITS_READ("account/rateLimits/read"),
    LOGIN_START("account/login/start"), LOGIN_CANCEL("account/login/cancel"), SKILLS_LIST("skills/list");
    private final String method;
    ManagementRequest(String method) { this.method = method; }
    public String method() { return method; }
}
