package com.softino.notifier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A page of notification history, as returned by
 * {@link NotifierApi#listNotifications(HistoryQuery)}.
 */
public final class HistoryPage {

    private final List<SendResult> items;
    private final String nextCursor;

    public HistoryPage(List<SendResult> items, String nextCursor) {
        this.items = items == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(items));
        this.nextCursor = nextCursor;
    }

    public List<SendResult> getItems() { return items; }
    public String getNextCursor() { return nextCursor; }
    public boolean hasNext() { return nextCursor != null && !nextCursor.isEmpty(); }
}
