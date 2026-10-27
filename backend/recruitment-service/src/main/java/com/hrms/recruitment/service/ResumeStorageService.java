package com.hrms.recruitment.service;

import com.hrms.recruitment.model.JobApplication;
import com.hrms.recruitment.repo.JobApplicationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Stores resumes in simulated storage nodes (folders) chosen by consistent hashing. */
@Service
public class ResumeStorageService {
    private final ConsistentHashRing ring;
    private final JobApplicationRepository apps;
    private final Path root;

    public ResumeStorageService(ConsistentHashRing ring, JobApplicationRepository apps,
                                @Value("${app.storage.root}") String root) {
        this.ring = ring;
        this.apps = apps;
        this.root = Paths.get(root).toAbsolutePath();
    }

    public void store(JobApplication app, MultipartFile file) throws IOException {
        String node = ring.getNode(app.getId());
        String name = Paths.get(Objects.requireNonNullElse(file.getOriginalFilename(), "resume.pdf")).getFileName().toString();
        Path dir = root.resolve(node);
        Files.createDirectories(dir);
        Path target = dir.resolve(app.getId() + "_" + name);
        file.transferTo(target);
        app.setResumeFileName(name);
        app.setStorageNode(node);
        app.setStoredPath(target.toString());
    }

    /** Recompute owner for every resume; physically move files whose owner changed. */
    public Map<String, Object> rebalance() throws IOException {
        List<Map<String, String>> moves = new ArrayList<>();
        int total = 0;
        for (JobApplication a : apps.findAll()) {
            if (a.getStoredPath() == null) continue;
            total++;
            String newNode = ring.getNode(a.getId());
            if (newNode.equals(a.getStorageNode())) continue;
            Path src = Paths.get(a.getStoredPath());
            Path dir = root.resolve(newNode);
            Files.createDirectories(dir);
            Path dst = dir.resolve(src.getFileName());
            if (Files.exists(src)) Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            moves.add(Map.of("applicationId", a.getId(), "from", String.valueOf(a.getStorageNode()), "to", newNode));
            a.setStorageNode(newNode);
            a.setStoredPath(dst.toString());
            apps.save(a);
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("totalResumes", total);
        r.put("moved", moves.size());
        r.put("movedPercent", total == 0 ? 0 : Math.round(moves.size() * 1000.0 / total) / 10.0);
        r.put("moves", moves);
        return r;
    }

    public Map<String, Long> distribution() {
        Map<String, Long> d = new LinkedHashMap<>();
        for (String n : ring.getNodes()) d.put(n, 0L);
        for (JobApplication a : apps.findAll())
            if (a.getStorageNode() != null) d.merge(a.getStorageNode(), 1L, Long::sum);
        return d;
    }

    /** Test helper: simulate N keys to show how evenly the ring spreads load. */
    public Map<String, Long> simulate(int keys) {
        Map<String, Long> d = new LinkedHashMap<>();
        for (String n : ring.getNodes()) d.put(n, 0L);
        for (int i = 0; i < keys; i++) d.merge(ring.getNode("key-" + i), 1L, Long::sum);
        return d;
    }

    /** Resolve a stored resume without allowing paths or symlinks outside this store. */
    public Path resumePath(JobApplication application) throws IOException {
        if(application.getStoredPath()==null)throw new java.nio.file.NoSuchFileException("Resume is unavailable");
        Path path=Paths.get(application.getStoredPath()).toRealPath();
        if(!path.startsWith(root.toRealPath())||!Files.isRegularFile(path))
            throw new java.nio.file.NoSuchFileException("Resume is unavailable");
        return path;
    }
}
