package com.jisong.bgm.model;

import java.util.List;

public record Round(
        String id,
        String name,
        long startMs,
        long endMs,
        List<TimelineItem> items
) {
    public Round {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public int questionCount() {
        int count = 0;
        for (TimelineItem item : items) {
            if (item.action() == ActionType.LOAD) {
                count++;
            }
        }
        return count;
    }
}
