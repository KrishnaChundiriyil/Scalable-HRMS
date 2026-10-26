package com.hrms.recruitment.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsistentHashRingTest {
    @Test void membershipChangesOnlyMoveKeysToAddedOrFromRemovedNode() {
        var ring=new ConsistentHashRing(100,"node-A,node-B,node-C");
        Map<String,String> before=new HashMap<>();
        for(int i=0;i<2000;i++)before.put("application-"+i,ring.getNode("application-"+i));
        ring.addNode("node-D");
        int moved=0;
        for(var entry:before.entrySet()) {
            String after=ring.getNode(entry.getKey());
            if(!entry.getValue().equals(after)){assertEquals("node-D",after);moved++;}
        }
        assertTrue(moved>0&&moved<1000);
        ring.removeNode("node-D");
        before.forEach((key,node)->assertEquals(node,ring.getNode(key)));
    }
    @Test void rejectsFilesystemPathsAndInvalidRingSize() {
        var ring=new ConsistentHashRing(100,"node-A");
        for(String name:List.of("../escape","/tmp/escape",".","..","node/subdir","node\\subdir",""))
            assertThrows(IllegalArgumentException.class,()->ring.addNode(name));
        assertThrows(IllegalArgumentException.class,()->new ConsistentHashRing(0,"node-A"));
        assertEquals(Set.of("node-A"),ring.getNodes());
    }
}
