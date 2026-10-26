package com.hrms.recruitment.config;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtException;
import static org.junit.jupiter.api.Assertions.*;

class SecurityConfigurationTest {
    private final byte[] key=new byte[48];
    private final SecurityConfiguration config=new SecurityConfiguration();
    private String token(String issuer,String audience,Date expires,String subject)throws Exception {
        var claims=new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject)
            .claim("roles",List.of("HR"));
        if(expires!=null)claims.expirationTime(expires);
        var jwt=new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),claims.build());
        jwt.sign(new MACSigner(key));return jwt.serialize();
    }
    @Test void acceptsSharedKeyWithRecruitmentAudience()throws Exception {
        new java.security.SecureRandom().nextBytes(key);
        var decoder=config.jwtDecoder(Base64.getEncoder().encodeToString(key),"","issuer","recruitment");
        var jwt=decoder.decode(token("issuer","recruitment",new Date(System.currentTimeMillis()+60000),"EMP1"));
        assertEquals("EMP1",jwt.getSubject());assertEquals(List.of("HR"),jwt.getClaimAsStringList("roles"));
    }
    @Test void rejectsWrongIssuerAudienceExpiredMissingExpiryOrInvalidSubject()throws Exception {
        new java.security.SecureRandom().nextBytes(key);
        var decoder=config.jwtDecoder(Base64.getEncoder().encodeToString(key),"","issuer","recruitment");
        var future=new Date(System.currentTimeMillis()+60000);
        for(String value:List.of(token("wrong","recruitment",future,"EMP1"),token("issuer","leave",future,"EMP1"),
            token("issuer","recruitment",new Date(1),"EMP1"),token("issuer","recruitment",null,"EMP1"),
            token("issuer","recruitment",future,"../EMP1")))
            assertThrows(JwtException.class,()->decoder.decode(value));
    }
    @Test void failsStartupWithoutStrongKey() {
        assertThrows(IllegalArgumentException.class,()->config.jwtDecoder("","","issuer","recruitment"));
    }
}
