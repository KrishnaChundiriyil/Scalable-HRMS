package com.hrms.employee.service;
import com.hrms.employee.dto.EmployeeResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;
import java.time.Duration;

@Service
public class EmployeeCacheService {
 private final StringRedisTemplate redis; private final ObjectMapper mapper;
 public EmployeeCacheService(StringRedisTemplate redis,ObjectMapper mapper){this.redis=redis;this.mapper=mapper;}
 public EmployeeResponse get(String id){try{String value=redis.opsForValue().get("hrms:employee:profile:"+id);if(value!=null)return mapper.readValue(value,EmployeeResponse.class);}catch(Exception ignored){}return null;}
 public void put(EmployeeResponse e){try{redis.opsForValue().set("hrms:employee:profile:"+e.employeeId(),mapper.writeValueAsString(e),Duration.ofMinutes(10));}catch(Exception ignored){}}
 public void evict(String id){try{redis.delete("hrms:employee:profile:"+id);}catch(Exception ignored){}}
 public void cacheSuggestions(String key,String json){try{redis.opsForValue().set("hrms:suggest:"+key,json,Duration.ofMinutes(5));}catch(Exception ignored){}}
 public String getSuggestions(String key){try{return redis.opsForValue().get("hrms:suggest:"+key);}catch(RedisConnectionFailureException ex){return null;}}
}
