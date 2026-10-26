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
 public JwtAuthFilter(JwtService jwtService){this.jwtService=jwtService;}
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  String header=request.getHeader("Authorization");
  if(header!=null&&header.startsWith("Bearer ")){try{Claims c=jwtService.parse(header.substring(7));String role=c.get("role",String.class);String id=c.getSubject();var auth=new UsernamePasswordAuthenticationToken(id,null,List.of(new SimpleGrantedAuthority("ROLE_"+role)));auth.setDetails(c.get("email",String.class));SecurityContextHolder.getContext().setAuthentication(auth);}catch(Exception ignored){SecurityContextHolder.clearContext();}}
  chain.doFilter(request,response);
 }
}
