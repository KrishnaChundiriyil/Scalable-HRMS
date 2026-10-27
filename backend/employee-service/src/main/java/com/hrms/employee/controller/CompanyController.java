package com.hrms.employee.controller;

import com.hrms.employee.repository.EmployeeRepository;
import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/company")
public class CompanyController {
    private final MongoTemplate mongo;
    private final EmployeeRepository employees;
    public CompanyController(MongoTemplate mongo,EmployeeRepository employees){this.mongo=mongo;this.employees=employees;}
    @GetMapping public Map<String,Object> company(){
        var content=mongo.findById("company",Document.class,"company_content");
        var result=new LinkedHashMap<String,Object>();
        if(content!=null){result.putAll(content);result.remove("_id");result.remove("_class");}
        result.putIfAbsent("name","Alder & Co.");
        var staff=employees.findAll().stream().filter(e->e.isActive()&&!"CANDIDATE".equals(e.getRole())).toList();
        result.put("peopleCount",staff.size());
        result.put("departments",staff.stream().map(e->e.getDepartment()).filter(Objects::nonNull).distinct().sorted().toList());
        return result;
    }
    @PutMapping public Map<String,Object> update(@RequestBody Map<String,Object> body){
        var content=new Document("_id","company");
        for(String field:List.of("name","tagline","about","mission","location","values","announcements"))
            if(body.containsKey(field))content.put(field,body.get(field));
        mongo.save(content,"company_content");return company();
    }
}
