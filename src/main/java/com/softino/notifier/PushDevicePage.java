package com.softino.notifier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * One page of registered Web Push devices, as returned by
 * {@link NotifierApi#listPushDevices(PushDeviceQuery)}.
 */
public final class PushDevicePage {

    private final List<PushDevice> items;
    private final int limit;
    private final int offset;

    public PushDevicePage(List<PushDevice> items, int limit, int offset) {
        this.items = items == null ? Collections.<PushDevice>emptyList()
                : Collections.unmodifiableList(new ArrayList<PushDevice>(items));
        this.limit = limit;
        this.offset = offset;
    }

    public List<PushDevice> getItems() {
        return items;
    }

    /**
     * The page size the service applied; do not assume the requested one was honoured, it caps
     * the value it actually used.
     */
    public int getLimit() {
        return limit;
    }

    /** The offset the service was given. */
    public int getOffset() {
        return offset;
    }

    /** Whether another page may exist. */
    public boolean hasMore() {
        return limit > 0 && items.size() >= limit;
    }

    public static PushDevicePage from(JsonObject o) {
        List<PushDevice> items = new ArrayList<>();
        if (o.has("items") && o.get("items").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("items")) {
                if (el.isJsonObject()) {
                    items.add(PushDevice.from(el.getAsJsonObject()));
                }
            }
        }
        int limit = o.has("limit") && !o.get("limit").isJsonNull() ? o.get("limit").getAsInt() : 0;
        int offset = o.has("offset") && !o.get("offset").isJsonNull() ? o.get("offset").getAsInt() : 0;
        return new PushDevicePage(items, limit, offset);
    }
}
