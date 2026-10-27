package com.scalablehrms.payroll.activity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Personal convenience feed, separate from permanent domain and audit records. */
@RestController
public class RecentActivityController implements HandlerInterceptor, WebMvcConfigurer {
    public static final Duration RETENTION = Duration.ofHours(48);
    private static final String COLLECTION = "recent_activity";
    private final MongoTemplate mongo;
    private Clock clock = Clock.systemUTC();
    public RecentActivityController(MongoTemplate mongo) { this.mongo=mongo; }
    @jakarta.annotation.PostConstruct public void indexes() {
        mongo.indexOps(COLLECTION).ensureIndex(new Index().on("actorId",Sort.Direction.ASC).on("createdAt",Sort.Direction.DESC));
        // TTL cleanup is asynchronous; the API also filters expiresAt explicitly.
        mongo.indexOps(COLLECTION).ensureIndex(new Index().on("expiresAt",Sort.Direction.ASC).expire(Duration.ZERO));
    }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(this).addPathPatterns("/api/**"); }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        Principal actor=request.getUserPrincipal();
        if(actor!=null)request.setAttribute("activity.actor",actor.getName());
        return true;
    }
    @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error) {
        Object actor=request.getAttribute("activity.actor");
        if(actor==null||error!=null||response.getStatus()<200||response.getStatus()>=300) return;
        String[] action=describe(request.getMethod(),request.getRequestURI(),actor.toString());
        if(action==null) return;
        Instant now=clock.instant();
        Document item=new Document("_id",UUID.randomUUID().toString()).append("actorId",actor.toString())
            .append("title",action[0]).append("href",action[1]).append("source","Payroll")
            .append("createdAt",Date.from(now)).append("expiresAt",Date.from(now.plus(RETENTION)));
        try { mongo.insert(item,COLLECTION); }
        catch(RuntimeException failure) { LoggerFactory.getLogger(getClass()).warn("Recent activity could not be saved; action remains in its domain record: {}",failure.getClass().getSimpleName()); }
    }
    @GetMapping("/api/payroll/me/activity")
    public List<Map<String,Object>> recent(Principal actor) {
        Instant now=clock.instant();
        Query query=new Query(Criteria.where("actorId").is(actor.getName()).and("expiresAt").gt(Date.from(now)))
            .with(Sort.by(Sort.Direction.DESC,"createdAt")).limit(50);
        var result=new ArrayList<Map<String,Object>>();
        for(Document item:mongo.find(query,Document.class,COLLECTION)) {
            var view=new LinkedHashMap<String,Object>();
            view.put("id",item.getString("_id"));view.put("title",item.getString("title"));
            view.put("href",item.getString("href"));view.put("source",item.getString("source"));
            view.put("createdAt",item.getDate("createdAt").toInstant().toString());
            view.put("expiresAt",item.getDate("expiresAt").toInstant().toString());result.add(view);
        }

        result.sort(Comparator.comparing(item->Instant.parse(item.get("createdAt").toString()),Comparator.reverseOrder()));
        return result.stream().limit(50).toList();
    }
    static String[] describe(String method,String path,String actor) {
        if(method.equals("POST")&&path.equals("/api/payroll"))return new String[]{"Created a monthly payroll record","/payroll"};
        return null;
    }
}
