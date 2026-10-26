package com.hrms.employee.security;
import io.jsonwebtoken.Claims;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
 private final JwtService jwtService;
 @org.springframework.beans.factory.annotation.Value("${app.leave-directory-token:}") private String directoryToken;
 public JwtAuthFilter(JwtService jwtService){this.jwtService=jwtService;}
 @jakarta.annotation.PostConstruct void validateDirectoryCredential(){if(!directoryToken.isBlank()&&directoryToken.length()<32)throw new IllegalArgumentException("Directory credential must contain at least 32 characters");}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  String header=request.getHeader("Authorization");
  if(header!=null&&header.startsWith("Bearer ")){
   if(request.getMethod().equals("GET")&&request.getServletPath().startsWith("/api/internal/employees/")
     &&!directoryToken.isBlank()&&java.security.MessageDigest.isEqual(directoryToken.getBytes(java.nio.charset.StandardCharsets.UTF_8),header.substring(7).getBytes(java.nio.charset.StandardCharsets.UTF_8))){
    SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("leave-directory",null,List.of(new SimpleGrantedAuthority("ROLE_LEAVE_DIRECTORY"))));
    chain.doFilter(request,response);return;
   }
   try{Claims c=jwtService.parse(header.substring(7));String role=c.get("role",String.class);String id=c.getSubject();var auth=new UsernamePasswordAuthenticationToken(id,null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));auth.setDetails(c.get("email",String.class));SecurityContextHolder.getContext().setAuthentication(auth);}catch(Exception ignored){SecurityContextHolder.clearContext();}}
  chain.doFilter(request,response);
 }
}
