package com.scalablehrms.leave.activity;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

class RecentActivityControllerTest {
    private final MongoTemplate mongo=mock(MongoTemplate.class);
    private final RecentActivityController feed=new RecentActivityController(mongo);
    private final Instant now=Instant.parse("2026-10-27T10:00:00Z");
    private MockHttpServletRequest action() {
        var request=new MockHttpServletRequest("POST","/api/leaves");
        request.setUserPrincipal(()->"EMP0007");
        feed.preHandle(request,new MockHttpServletResponse(),new Object());
        return request;
    }
    @Test void successfulActionBelongsToAuthenticatedActorAndExpiresAfterExactly48Hours() {
        ReflectionTestUtils.setField(feed,"clock",Clock.fixed(now,ZoneOffset.UTC));
        var request=action();request.setContent("private reason or salary".getBytes());
        feed.afterCompletion(request,new MockHttpServletResponse(),new Object(),null);
        var saved=ArgumentCaptor.forClass(Document.class);
        verify(mongo).insert(saved.capture(),eq("recent_activity"));
        assertEquals("EMP0007",saved.getValue().getString("actorId"));
        assertEquals(now,saved.getValue().getDate("createdAt").toInstant());
        assertEquals(now.plus(Duration.ofHours(48)),saved.getValue().getDate("expiresAt").toInstant());
        assertFalse(saved.getValue().toJson().contains("private reason or salary"));
    }
    @Test void failedAnonymousAndReadRequestsDoNotCreateActivity() {
        var failed=new MockHttpServletResponse();failed.setStatus(403);
        feed.afterCompletion(action(),failed,new Object(),null);
        feed.afterCompletion(new MockHttpServletRequest("POST","/api/leaves"),new MockHttpServletResponse(),new Object(),null);
        var read=action();read.setMethod("GET");
        feed.afterCompletion(read,new MockHttpServletResponse(),new Object(),null);
        verifyNoInteractions(mongo);
    }
    @Test void queryUsesSignedInActorAndExcludesExpiredRowsEvenBeforeTtlCleanup() {
        ReflectionTestUtils.setField(feed,"clock",Clock.fixed(now,ZoneOffset.UTC));
        when(mongo.find(any(Query.class),eq(Document.class),eq("recent_activity"))).thenReturn(List.of());
        feed.recent(()->"EMP0007");
        var query=ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(),eq(Document.class),eq("recent_activity"));
        assertEquals("EMP0007",query.getValue().getQueryObject().getString("actorId"));
        assertEquals(Date.from(now),((Document)query.getValue().getQueryObject().get("expiresAt")).get("$gt"));
        assertEquals(50,query.getValue().getLimit());
    }
    @Test void activityFailureDoesNotTurnASuccessfulBusinessActionIntoAnError() {
        when(mongo.insert(any(Document.class),eq("recent_activity"))).thenThrow(new IllegalStateException("unavailable"));
        assertDoesNotThrow(()->feed.afterCompletion(action(),new MockHttpServletResponse(),new Object(),null));
    }
}
