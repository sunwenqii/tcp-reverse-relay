package dev.sunwenqi.tcpreverserelay.observability;

import java.util.List;

public record JournalEntry(long id, long time, String kind, String level, String category,
                           Integer port, List<Integer> ports, String message, String detail, String source) {}
