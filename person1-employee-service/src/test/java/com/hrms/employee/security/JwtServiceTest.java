package com.hrms.employee.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private final byte[] key = new byte[48];
    private JwtService service() {
        new java.security.SecureRandom().nextBytes(key);
        var service = new JwtService();
        ReflectionTestUtils.setField(service,"secret","");
        ReflectionTestUtils.setField(service,"base64Secret",Base64.getEncoder().encodeToString(key));
        ReflectionTestUtils.setField(service,"issuer","hrms-employee-service");
        ReflectionTestUtils.setField(service,"audience","hrms-leave-service");
        ReflectionTestUtils.setField(service,"expirationMs",60000L);
        return service;
    }
    @Test void loginTokenMatchesLeaveContractAndKeepsOriginalClaims() {
        var service=service();
        var token=service.generate("EMP01001","employee@example.test","MANAGER");
        var parsed=Jwts.parser().verifyWith(Keys.hmacShaKeyFor(key)).build().parseSignedClaims(token);
        assertEquals("HS256",parsed.getHeader().getAlgorithm());
        var claims=service.parse(token);
        assertEquals("EMP01001",claims.getSubject());
        assertEquals("MANAGER",claims.get("role"));
        assertEquals(List.of("MANAGER"),claims.get("roles"));
        assertEquals("employee@example.test",claims.get("email"));
        assertNotNull(claims.getExpiration());
    }
    @Test void rejectsWrongIssuerAndAudienceEvenWithCorrectSignature() {
        var service=service();
        for(var values:List.of(List.of("other","hrms-leave-service"),List.of("hrms-employee-service","other"))) {
            var token=Jwts.builder().subject("EMP01001").issuer(values.get(0)).audience().add(values.get(1)).and()
                .expiration(new Date(System.currentTimeMillis()+60000)).signWith(Keys.hmacShaKeyFor(key),Jwts.SIG.HS256).compact();
            assertThrows(JwtException.class,()->service.parse(token));
        }
    }
    @Test void noDefaultSigningCredential() {
        var service=service();
        ReflectionTestUtils.setField(service,"base64Secret","");
        assertThrows(io.jsonwebtoken.security.WeakKeyException.class,service::validateKey);
    }
}
