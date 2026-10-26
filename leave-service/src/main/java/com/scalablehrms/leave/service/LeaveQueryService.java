package com.scalablehrms.leave.service;

import com.scalablehrms.leave.domain.*;
import com.scalablehrms.leave.dto.*;
import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.stereotype.Service;

/** Filtering, sorting, counting and pagination happen in MongoDB, before materializing records. */
@Service
public class LeaveQueryService {
    private final MongoTemplate mongo;
    private final AccountStore store;
    public LeaveQueryService(MongoTemplate mongo,AccountStore store) { this.mongo=mongo; this.store=store; }
    private Document stage(String operator,Object value) { return new Document(operator,value); }
    private Document aggregate(List<Document> stages) {
        List<AggregationOperation> operations=stages.stream().<AggregationOperation>map(doc -> context -> doc).toList();
        return mongo.aggregate(Aggregation.newAggregation(operations).withOptions(AggregationOptions.builder()
            .allowDiskUse(true).maxTime(java.time.Duration.ofSeconds(5)).build()),"leave_accounts",Document.class).getUniqueMappedResult();
    }
    public PageResult<LeaveView> requests(Set<String> scope,LeaveSearch filter) {
        store.importLegacy();
        var stages=new ArrayList<Document>();
        if (scope!=null) stages.add(stage("$match",new Document("_id",new Document("$in",scope))));
        if (filter.employeeId()!=null) stages.add(stage("$match",new Document("_id",filter.employeeId())));
        stages.add(stage("$unwind","$requests"));
        var match=new Document();
        if (filter.type()!=null) match.put("requests.type",filter.type());
        if (filter.status()!=null) match.put("requests.status",filter.status().name());
        if (filter.from()!=null) match.put("requests.endDate",new Document("$gte",java.util.Date.from(filter.from().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())));
        if (filter.to()!=null) match.put("requests.startDate",new Document("$lte",java.util.Date.from(filter.to().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant())));
        if (filter.search()!=null && !filter.search().isBlank()) {
            var regex=java.util.regex.Pattern.compile(java.util.regex.Pattern.quote(filter.search().trim()),java.util.regex.Pattern.CASE_INSENSITIVE);
            match.put("$or",List.of(new Document("_id",regex),new Document("requests.reason",regex)));
        }
        var candidate=new Document();
        match.forEach((key,value) -> { if (key.startsWith("requests.")) candidate.put(key.substring(9),value); });
        if (!candidate.isEmpty()) stages.add(stages.size()-1,stage("$match",new Document("requests",new Document("$elemMatch",candidate))));
        if (!match.isEmpty()) stages.add(stage("$match",match));
        stages.add(stage("$sort",new Document("requests.createdAt",-1).append("_id",1).append("requests._id",1)));
        stages.add(stage("$facet",new Document("items",List.of(stage("$skip",(long)filter.page()*filter.size()),
            stage("$limit",filter.size()),stage("$project",new Document("employeeId","$_id").append("request","$requests").append("_id",0))))
            .append("total",List.of(stage("$count","count")))));
        Document result=aggregate(stages);
        if (result==null) return PageResult.of(List.of(),filter.page(),filter.size(),0);
        List<LeaveView> items=new ArrayList<>();
        for (Document item:result.getList("items",Document.class,List.of()))
            items.add(LeaveView.of(item.getString("employeeId"),mongo.getConverter().read(LeaveRequest.class,item.get("request",Document.class))));
        var counts=result.getList("total",Document.class,List.of());
        long total=counts.isEmpty()?0:((Number)counts.getFirst().get("count")).longValue();
        return PageResult.of(items,filter.page(),filter.size(),total);
    }
    public PageResult<LeaveAuditEvent> audit(String employee,String requestId,int page,int size) {
        new LeaveSearch(employee,null,null,null,null,null,page,size);
        store.load(employee); // Lazy-import old data, without fabricating historical events.
        var stages=new ArrayList<Document>();
        stages.add(stage("$match",new Document("_id",employee)));
        stages.add(stage("$unwind","$auditTrail"));
        if (requestId!=null) stages.add(stage("$match",new Document("auditTrail.leaveId",requestId)));
        stages.add(stage("$sort",new Document("auditTrail.sequence",-1)));
        stages.add(stage("$facet",new Document("items",List.of(stage("$skip",(long)page*size),stage("$limit",size),
            stage("$replaceRoot",new Document("newRoot","$auditTrail")))).append("total",List.of(stage("$count","count")))));
        Document result=aggregate(stages);
        if (result==null) return PageResult.of(List.of(),page,size,0);
        var events=result.getList("items",Document.class,List.of()).stream().map(doc -> mongo.getConverter().read(LeaveAuditEvent.class,doc)).toList();
        var counts=result.getList("total",Document.class,List.of());
        return PageResult.of(events,page,size,counts.isEmpty()?0:((Number)counts.getFirst().get("count")).longValue());
    }
}
