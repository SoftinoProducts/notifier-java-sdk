package com.softino.notifier.kavenegar.models;

import com.google.gson.JsonObject;

/** Kavenegar-compatible account config result. */
public class AccountConfigResult {

    private final String apiLogs;
    private final String dailyReport;
    private final String debugMode;
    private final String defaultSender;
    private final String resendFailed;
    private final int minCreditAlarm;

    public AccountConfigResult(String apiLogs, String dailyReport, String debugMode,
                               String defaultSender, String resendFailed, int minCreditAlarm) {
        this.apiLogs = apiLogs;
        this.dailyReport = dailyReport;
        this.debugMode = debugMode;
        this.defaultSender = defaultSender;
        this.resendFailed = resendFailed;
        this.minCreditAlarm = minCreditAlarm;
    }

    @SuppressWarnings("unused")
    public AccountConfigResult(JsonObject json) {
        this(
                json.has("apilogs") ? json.get("apilogs").getAsString() : null,
                json.has("dailyreport") ? json.get("dailyreport").getAsString() : null,
                json.has("debugmode") ? json.get("debugmode").getAsString() : null,
                json.has("defaultsender") ? json.get("defaultsender").getAsString() : null,
                json.has("resendfailed") ? json.get("resendfailed").getAsString() : null,
                json.has("mincreditalarm") ? json.get("mincreditalarm").getAsInt() : 0);
    }

    public String getApiLogs() {
        return apiLogs;
    }

    public String getDailyReport() {
        return dailyReport;
    }

    public String getDebugMode() {
        return debugMode;
    }

    public String getDefaultSender() {
        return defaultSender;
    }

    public String getResendFailed() {
        return resendFailed;
    }

    public int getMinCreditAlarm() {
        return minCreditAlarm;
    }
}
