package com.hrms.employee.service;
import com.hrms.employee.repository.EmployeeRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class EmployeeIdGenerator {
 private final StringRedisTemplate redis; private final EmployeeRepository repository; private final AtomicLong fallback;
 public EmployeeIdGenerator(StringRedisTemplate redis,EmployeeRepository repository){this.redis=redis;this.repository=repository;this.fallback=new AtomicLong(1000);}
 public String nextId(){long n;try{Long value=redis.opsForValue().increment("hrms:employee:id:counter");n=value==null?fallback.incrementAndGet():value+1000;}catch(RedisConnectionFailureException ex){n=fallback.incrementAndGet();}String id;do{id="EMP"+String.format("%05d",n++);}while(repository.existsByEmployeeId(id));return id;}
}
