package com.hrms.employee.service;

import com.hrms.employee.dto.*;
import com.hrms.employee.entity.Employee;
import com.hrms.employee.repository.EmployeeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class EmployeeService {
 private final EmployeeRepository repo; private final EmployeeIdGenerator ids; private final PasswordEncoder encoder; private final EmployeeCacheService cache;
 public EmployeeService(EmployeeRepository repo,EmployeeIdGenerator ids,PasswordEncoder encoder,EmployeeCacheService cache){this.repo=repo;this.ids=ids;this.encoder=encoder;this.cache=cache;}
 public EmployeeResponse register(RegisterRequest r,boolean privileged){String email=r.email().trim().toLowerCase(Locale.ROOT);if(repo.existsByEmailIgnoreCase(email))throw new ResponseStatusException(HttpStatus.CONFLICT,"Email is already registered");String role=privileged&&r.role()!=null&&!r.role().isBlank()?r.role().trim().toUpperCase(Locale.ROOT):"EMPLOYEE";if(!Set.of("ADMIN","HR","EMPLOYEE").contains(role))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Role must be ADMIN, HR or EMPLOYEE");Employee e=new Employee();e.setEmployeeId(ids.nextId());e.setName(r.name().trim());e.setEmail(email);e.setPasswordHash(encoder.encode(r.password()));e.setDepartment(r.department().trim());e.setRole(role);e.setJobTitle(r.jobTitle());e.setPhone(r.phone());return EmployeeResponse.from(repo.save(e));}
 public Employee authenticate(String email,String password){Employee e=repo.findByEmailIgnoreCase(email.trim()).orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid email or password"));if(!encoder.matches(password,e.getPasswordHash()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid email or password");return e;}
 public Employee getEntity(String id){return repo.findByEmployeeId(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Employee not found: "+id));}
 public EmployeeResponse get(String id){EmployeeResponse cached=cache.get(id);if(cached!=null)return cached;EmployeeResponse result=EmployeeResponse.from(getEntity(id));cache.put(result);return result;}
 public List<EmployeeResponse> search(String name,String department){String n=name==null?"":name.trim(),d=department==null?"":department.trim();List<Employee> list=repo.findByNameContainingIgnoreCaseAndDepartmentContainingIgnoreCase(n,d);return list.stream().map(EmployeeResponse::from).toList();}
 public EmployeeResponse update(String id,ProfileUpdateRequest r){Employee e=getEntity(id);if(r.name()!=null&&!r.name().isBlank())e.setName(r.name().trim());if(r.department()!=null&&!r.department().isBlank())e.setDepartment(r.department().trim());if(r.jobTitle()!=null)e.setJobTitle(r.jobTitle());if(r.phone()!=null)e.setPhone(r.phone());Employee saved=repo.save(e);cache.evict(id);return EmployeeResponse.from(saved);}
 public void delete(String id){Employee e=getEntity(id);repo.delete(e);cache.evict(id);}
 public List<String> suggest(String type,String prefix){String p=prefix==null?"":prefix.trim();if(p.isEmpty())return List.of();String cacheKey=type.toLowerCase(Locale.ROOT)+":"+p.toLowerCase(Locale.ROOT);String cached=cache.getSuggestions(cacheKey);if(cached!=null){try{return new com.fasterxml.jackson.databind.ObjectMapper().readValue(cached,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});}catch(Exception ignored){}}
  Trie trie=new Trie();if(type.equalsIgnoreCase("department"))repo.findAll().stream().map(Employee::getDepartment).distinct().forEach(trie::insert);else repo.findAll().stream().map(Employee::getName).distinct().forEach(trie::insert);List<String> result=trie.search(p,10);try{cache.cacheSuggestions(cacheKey,new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(result));}catch(Exception ignored){}return result;
 }
 public boolean hasEmployees(){return repo.count()>0;}
}
