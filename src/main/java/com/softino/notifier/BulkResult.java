package com.softino.notifier;

/** Result of a bulk send, as returned by {@code POST /v1/notifications/bulk}. */
public final class BulkResult {

    private final String batchId;
    private final int count;

    public BulkResult(String batchId, int count) {
        this.batchId = batchId;
        this.count = count;
    }

    public String getBatchId() { return batchId; }
    public int getCount() { return count; }

    @Override
    public String toString() {
        return "BulkResult{batchId=" + batchId + ", count=" + count + "}";
    }
}
