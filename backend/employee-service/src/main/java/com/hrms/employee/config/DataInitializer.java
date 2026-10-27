package com.hrms.employee.config;
import com.hrms.employee.dto.RegisterRequest;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.employee.service.EmployeeService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataInitializer {
 @Bean @ConditionalOnProperty(name="app.bootstrap.enabled",havingValue="true")
 CommandLineRunner createDefaultAdmin(EmployeeRepository repository,EmployeeService service,
   @Value("${app.bootstrap.email:}")String email,@Value("${app.bootstrap.password:}")String password){
  if(email.isBlank()||password.length()<8)throw new IllegalArgumentException("Bootstrap requires an explicit email and password (at least 8 characters)");
  return args->{if(repository.findByEmailIgnoreCase(email).isEmpty())service.register(new RegisterRequest("System Admin",email,password,"Human Resources","System Administrator","","ADMIN"),true);};
 }
}
