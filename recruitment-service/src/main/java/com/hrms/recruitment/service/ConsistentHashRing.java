package com.hrms.recruitment.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Consistent hash ring with virtual nodes. Key -> first virtual node clockwise. */
@Component
public class ConsistentHashRing {
    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final Set<String> nodes = new LinkedHashSet<>();
    private final int virtualNodes;

    public ConsistentHashRing(@Value("${app.storage.virtual-nodes:100}") int virtualNodes,
                              @Value("${app.storage.initial-nodes}") String initial) {
        this.virtualNodes = virtualNodes;
        if(virtualNodes<1||virtualNodes>1000)throw new IllegalArgumentException("virtual-nodes must be between 1 and 1000");
        for (String n : initial.split(",")) if (!n.isBlank()) addNode(n.trim());
    }

    public synchronized void addNode(String node) {
        validate(node);
        if (!nodes.add(node)) return;
        for (int i = 0; i < virtualNodes; i++) ring.put(hash(node + "#" + i), node);
    }

    public synchronized void removeNode(String node) {
        validate(node);
        if (!nodes.remove(node)) return;
        for (int i = 0; i < virtualNodes; i++) ring.remove(hash(node + "#" + i));
    }

    public synchronized String getNode(String key) {
        if (ring.isEmpty()) throw new IllegalStateException("No storage nodes available");
        Map.Entry<Long, String> e = ring.ceilingEntry(hash(key));
        return (e != null ? e : ring.firstEntry()).getValue();
    }

    public synchronized Set<String> getNodes() { return new LinkedHashSet<>(nodes); }
    public int getVirtualNodes() { return virtualNodes; }

    private static void validate(String node) {
        if(node==null||!node.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Storage node must be a simple name of 1-64 letters, digits, underscores or hyphens");
    }

    /** First 8 bytes of MD5 as a positive long. */
    static long hash(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            long h = 0;
            for (int i = 0; i < 8; i++) h = (h << 8) | (d[i] & 0xff);
            return h & Long.MAX_VALUE;
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}
