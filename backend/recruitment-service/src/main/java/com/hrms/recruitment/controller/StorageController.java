package com.hrms.recruitment.controller;

import com.hrms.recruitment.service.ConsistentHashRing;
import com.hrms.recruitment.service.ResumeStorageService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/storage")
public class StorageController {
    private final ConsistentHashRing ring;
    private final ResumeStorageService storage;

    public StorageController(ConsistentHashRing ring, ResumeStorageService storage) {
        this.ring = ring;
        this.storage = storage;
    }

    @GetMapping("/nodes")
    public Map<String, Object> nodes() {
        return Map.of("nodes", ring.getNodes(), "virtualNodesPerNode", ring.getVirtualNodes(),
                "resumesPerNode", storage.distribution());
    }

    @PostMapping("/nodes/{name}")
    public Map<String, Object> add(@PathVariable String name) throws Exception {
        ring.addNode(name);
        Map<String, Object> r = new LinkedHashMap<>(storage.rebalance());
        r.put("event", "added " + name);
        r.put("resumesPerNode", storage.distribution());
        return r;
    }

    @DeleteMapping("/nodes/{name}")
    public Map<String, Object> remove(@PathVariable String name) throws Exception {
        if (ring.getNodes().size() <= 1) throw new IllegalStateException("Cannot remove the last node");
        ring.removeNode(name);
        Map<String, Object> r = new LinkedHashMap<>(storage.rebalance());
        r.put("event", "removed " + name);
        r.put("resumesPerNode", storage.distribution());
        return r;
    }

    /** Shows load balance of the ring using synthetic keys (no files needed). */
    @GetMapping("/simulate")
    public Map<String, Long> simulate(@RequestParam(defaultValue = "10000") int keys) {
        return storage.simulate(Math.min(keys, 1_000_000));
    }
}
