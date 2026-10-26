package com.hrms.employee.config;
import com.hrms.employee.dto.RegisterRequest;
import com.hrms.employee.repository.EmployeeRepository;
import com.hrms.employee.service.EmployeeService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DataInitializer {
 @Bean CommandLineRunner createDefaultAdmin(EmployeeRepository repository,EmployeeService service){return args->{if(repository.findByEmailIgnoreCase("admin@hrms.local").isEmpty())service.register(new RegisterRequest("System Admin","admin@hrms.local","Admin@12345","Human Resources","System Administrator","", "ADMIN"),true);};}
}
