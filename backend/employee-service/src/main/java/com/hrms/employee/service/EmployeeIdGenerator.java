package com.hrms.employee.service;
import com.hrms.employee.repository.EmployeeRepository;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class EmployeeIdGenerator {
 private final MongoTemplate mongo; private final EmployeeRepository repository;
 public EmployeeIdGenerator(MongoTemplate mongo,EmployeeRepository repository){this.mongo=mongo;this.repository=repository;}
 public String nextId(){for(;;){Document counter=mongo.findAndModify(Query.query(Criteria.where("_id").is("employee")),new Update().inc("value",1L),FindAndModifyOptions.options().upsert(true).returnNew(true),Document.class,"employee_sequences");String id="EMP"+String.format("%05d",counter.getLong("value")+1000);if(!repository.existsByEmployeeId(id))return id;}}
}
