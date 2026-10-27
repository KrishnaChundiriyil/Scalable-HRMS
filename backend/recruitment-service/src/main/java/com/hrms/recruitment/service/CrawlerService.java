package com.hrms.recruitment.service;

import com.hrms.recruitment.model.CrawledJob;
import com.hrms.recruitment.repo.CrawledJobRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.*;

/**
 * BFS web crawler: URL frontier (queue) + Redis visited set (SADD returns 0 if seen).
 * Job listings are elements with class "job" (data-title/company/location or child .title/.company/.location).
 * Stays on the seed's host (politeness / scope control).
 */
@Service
public class CrawlerService {
    private static final String VISITED = "crawler:visited";
    private final StringRedisTemplate redis;
    private final CrawledJobRepository repo;
    private final int defaultMaxPages;

    public CrawlerService(StringRedisTemplate redis, CrawledJobRepository repo,
                          @Value("${app.crawler.max-pages:30}") int defaultMaxPages) {
        this.redis = redis;
        this.repo = repo;
        this.defaultMaxPages = defaultMaxPages;
    }

    public Map<String, Object> crawl(String seed, Integer maxPagesParam, boolean reset) {
        if (reset) redis.delete(VISITED);
        int maxPages = maxPagesParam == null ? defaultMaxPages : Math.min(maxPagesParam, 200);
        String host = URI.create(seed).getHost();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(seed);
        int visited = 0, skipped = 0, found = 0;
        List<String> log = new ArrayList<>();

        while (!queue.isEmpty() && visited < maxPages) {
            String url = queue.poll();
            Long added = redis.opsForSet().add(VISITED, url);
            if (added == null || added == 0) { skipped++; log.add("SKIP (already visited) " + url); continue; }
            try {
                Document doc = Jsoup.connect(url).userAgent("HRMS-JobCrawler/1.0").timeout(5000).get();
                visited++;
                log.add("VISIT " + url);
                for (Element el : doc.select(".job")) {
                    String jobUrl = el.hasAttr("data-url") ? el.absUrl("data-url") : url;
                    String key = jobUrl.equals(url) ? url + "#" + el.text().hashCode() : jobUrl;
                    if (repo.existsBySourceUrl(key)) continue;
                    CrawledJob j = new CrawledJob();
                    j.setTitle(pick(el, "title"));
                    j.setCompany(pick(el, "company"));
                    j.setLocation(pick(el, "location"));
                    j.setSourceUrl(key);
                    repo.save(j);
                    found++;
                }
                for (Element a : doc.select("a[href]")) {
                    String next = a.absUrl("href");
                    int hash = next.indexOf('#');
                    if (hash >= 0) next = next.substring(0, hash);
                    if (next.isBlank() || !next.startsWith("http")) continue;
                    try { if (!host.equals(URI.create(next).getHost())) continue; } catch (Exception e) { continue; }
                    if (!Boolean.TRUE.equals(redis.opsForSet().isMember(VISITED, next))) queue.add(next);
                }
            } catch (Exception e) {
                log.add("FAIL " + url + " -> " + e.getMessage());
            }
        }
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("seed", seed);
        r.put("pagesVisited", visited);
        r.put("skippedAlreadyVisited", skipped);
        r.put("newJobsFound", found);
        r.put("queueRemaining", queue.size());
        r.put("log", log);
        return r;
    }

    private String pick(Element el, String field) {
        if (el.hasAttr("data-" + field)) return el.attr("data-" + field);
        Element c = el.selectFirst("." + field);
        return c != null ? c.text() : "";
    }
}
