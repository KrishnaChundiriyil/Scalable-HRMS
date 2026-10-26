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
 @Value("${app.jwt.secret-base64:}") private String base64Secret;
 @Value("${app.jwt.issuer}") private String issuer;
 @Value("${app.jwt.audience}") private String audience;
 @Value("${app.jwt.recruitment-audience:hrms-recruitment-service}") private String recruitmentAudience;
 @jakarta.annotation.PostConstruct void validateKey(){key();}
 @Value("${app.jwt.expiration-ms}") private long expirationMs;
 private SecretKey key(){return Keys.hmacShaKeyFor(base64Secret.isBlank()?secret.getBytes(StandardCharsets.UTF_8):java.util.Base64.getDecoder().decode(base64Secret));}
 public String generate(String employeeId,String email,String role){long now=System.currentTimeMillis();return Jwts.builder().subject(employeeId).claim("email",email).claim("role",role).claim("roles",java.util.List.of(role)).issuer(issuer).audience().add(audience).add(recruitmentAudience).and().issuedAt(new Date(now)).expiration(new Date(now+expirationMs)).signWith(key(),Jwts.SIG.HS256).compact();}
 public Claims parse(String token){return Jwts.parser().verifyWith(key()).requireIssuer(issuer).requireAudience(audience).build().parseSignedClaims(token).getPayload();}
 public long getExpirationSeconds(){return expirationMs/1000;}
}
