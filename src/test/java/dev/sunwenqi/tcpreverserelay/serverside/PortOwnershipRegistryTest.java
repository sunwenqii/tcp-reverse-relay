package dev.sunwenqi.tcpreverserelay.serverside;

import dev.sunwenqi.tcpreverserelay.config.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class PortOwnershipRegistryTest {
    @TempDir Path dir;
    @Test void firstClaimReconnectTransferPauseAndPersistence() throws Exception {
        Path file=dir.resolve("server.properties"); Files.writeString(file,"mode=simple\nhandshake.port=10010\nadmin.port=19090\nforwards=18080\n");
        var registry=new PortOwnershipRegistry(file,List.of(18080),Set.of(),Map.of());
        registry.claim(18080,"device-A"); registry.claim(18080,"device-A");
        assertEquals("device-A",AppSettings.server(file).portOwners().get(18080));
        assertThrows(IllegalArgumentException.class,()->registry.claim(18080,"device-B")); assertEquals("device-A",registry.owner(18080));
        registry.publish(Set.of(18080),Set.of(18080),Map.of(18080,"device-A"));
        assertThrows(IllegalArgumentException.class,()->registry.claim(18080,"device-A"));
        registry.publish(Set.of(18080),Set.of(),Map.of(18080,"device-A")); registry.assign(18080,"device-B");
        registry.claim(18080,"device-B"); assertThrows(IllegalArgumentException.class,()->registry.claim(18080,"device-A"));
        assertEquals(Map.of(18081,"device-B"),registry.nextOwners(Set.of(18081),18080,18081));
        assertTrue(registry.nextOwners(Set.of(),null,null).isEmpty());
        registry.assign(18080,""); registry.claim(18080,"device-C"); assertEquals("device-C",registry.owner(18080));
        assertThrows(IllegalArgumentException.class,()->PortOwners.parse("18080:bad:id",List.of(18080)));
        assertThrows(IllegalArgumentException.class,()->PortOwners.parse("18081:a",List.of(18080)));
    }
    @Test void simultaneousClaimsHaveOneWinnerAndFailedPersistenceDoesNotPublish() throws Exception {
        Path file=dir.resolve("server.properties"); Files.writeString(file,"forwards=18080\n");
        var registry=new PortOwnershipRegistry(file,List.of(18080),Set.of(),Map.of()); var workers=Executors.newFixedThreadPool(2);
        try {
            var a=workers.submit(()->{try{registry.claim(18080,"a");return true;}catch(Exception e){return false;}});
            var b=workers.submit(()->{try{registry.claim(18080,"b");return true;}catch(Exception e){return false;}});
            assertNotEquals(a.get(),b.get()); assertEquals(registry.owner(18080),PortOwners.parse(Files.readString(file).split("port.owners=")[1].trim(),List.of(18080)).get(18080));
        } finally { workers.shutdownNow(); }
        var broken=new PortOwnershipRegistry(dir.resolve("missing/file"),List.of(18080),Set.of(),Map.of());
        assertThrows(java.io.IOException.class,()->broken.claim(18080,"a")); assertEquals("",broken.owner(18080));
    }
}
