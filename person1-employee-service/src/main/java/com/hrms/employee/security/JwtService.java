package com.hrms.employee.security;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Service
public class JwtService {
 @Value("${app.jwt.secret}") private String secret;
 @Value("${app.jwt.expiration-ms}") private long expirationMs;
 private SecretKey key(){return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));}
 public String generate(String employeeId,String email,String role){long now=System.currentTimeMillis();return Jwts.builder().subject(employeeId).claim("email",email).claim("role",role).issuedAt(new Date(now)).expiration(new Date(now+expirationMs)).signWith(key()).compact();}
 public Claims parse(String token){return Jwts.parser().verifyWith(key()).build().parseSignedClaims(token).getPayload();}
 public long getExpirationSeconds(){return expirationMs/1000;}
}
