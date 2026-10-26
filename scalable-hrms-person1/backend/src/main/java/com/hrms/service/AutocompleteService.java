package com.hrms.service;

import com.hrms.model.Employee;
import com.hrms.repository.EmployeeRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AutocompleteService {
    private final EmployeeRepository employees;
    private final StringRedisTemplate redis;
    private volatile Trie trie = new Trie();

    public AutocompleteService(EmployeeRepository employees, StringRedisTemplate redis) {
        this.employees = employees; this.redis = redis;
    }

    public List<String> suggest(String rawQuery) {
        String q = rawQuery == null ? "" : rawQuery.trim().toLowerCase(Locale.ROOT);
        if (q.length() < 1) return List.of();
        String cacheKey = "hrms:autocomplete:" + q;
        String cached = redis.opsForValue().get(cacheKey);
        if (cached != null) return cached.isBlank() ? List.of() : Arrays.asList(cached.split("\\u001F"));
        rebuild();
        List<String> result = trie.search(q, 8);
        redis.opsForValue().set(cacheKey, String.join("\u001F", result),
            java.time.Duration.ofMinutes(5));
        return result;
    }

    public void rebuild() {
        // Employee data changed: clear cached prefixes so suggestions cannot go stale.
        Set<String> keys = redis.keys("hrms:autocomplete:*");
        if (keys != null && !keys.isEmpty()) redis.delete(keys);
        Trie next = new Trie();
        for (Employee e : employees.findAll()) {
            next.insert(e.getFullName());
            if (e.getDepartment() != null) next.insert(e.getDepartment());
            if (e.getJobTitle() != null) next.insert(e.getJobTitle());
        }
        trie = next;
    }

    static class Trie {
        static class Node {
            Map<Character, Node> children = new HashMap<>();
            boolean end;
            String original;
        }
        private final Node root = new Node();
        void insert(String value) {
            if (value == null || value.isBlank()) return;
            Node node = root;
            for (char c : value.toLowerCase(Locale.ROOT).toCharArray())
                node = node.children.computeIfAbsent(c, ignored -> new Node());
            node.end = true; node.original = value;
        }
        List<String> search(String prefix, int limit) {
            Node node = root;
            for (char c : prefix.toCharArray()) {
                node = node.children.get(c);
                if (node == null) return List.of();
            }
            List<String> out = new ArrayList<>();
            collect(node, out, limit);
            return out;
        }
        void collect(Node node, List<String> out, int limit) {
            if (out.size() >= limit) return;
            if (node.end && node.original != null) out.add(node.original);
            for (Node child : node.children.values()) {
                if (out.size() >= limit) break;
                collect(child, out, limit);
            }
        }
    }
}
