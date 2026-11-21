package com.andrea.rxhome;

import java.util.Arrays;
import java.util.List;

public class SsdpTest {
    public static void main(String[] a) throws Exception {
        String lg = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nST: urn:lge-com:service:webos-second-screen:1\r\n"
            + "USN: uuid:1234-ab::urn:lge-com:service:webos-second-screen:1\r\nSERVER: WebOS/4.1.0 UPnP/1.0\r\n\r\n";
        Ssdp.Hit h = Ssdp.parse(lg, "192.168.1.30");
        assert h.ip.equals("192.168.1.30") && h.uuid.equals("1234-ab") && h.st.contains("webos-second-screen") : h.uuid;
        assert Ssdp.parse("HTTP/1.1 200 OK\r\nST: x\r\n\r\n", "1.1.1.1") == null; // no USN
        assert Ssdp.parse("usn: uuid:low\r\n", "1.1.1.1").uuid.equals("low"); // header names in any case

        // One device answers for several services: its root device's uuid is its id.
        List<Ssdp.Hit> hits = Arrays.asList(
            Ssdp.parse("ST: urn:schemas-upnp-org:service:RenderingControl:1\r\nUSN: uuid:zzz-embedded::urn:x\r\n", "1.1.1.9"),
            Ssdp.parse("ST: upnp:rootdevice\r\nUSN: uuid:root-1::upnp:rootdevice\r\n", "1.1.1.9"),
            Ssdp.parse("ST: upnp:rootdevice\r\nUSN: uuid:other::upnp:rootdevice\r\n", "1.1.1.8"));
        assert Ssdp.rootUuid(hits, "1.1.1.9").equals("root-1");
        assert Ssdp.rootUuid(hits, "1.1.1.7") == null;
        // Several root devices on one IP (seen live), in any order: always the same one, the smallest.
        List<Ssdp.Hit> roots = Arrays.asList(
            Ssdp.parse("ST: upnp:rootdevice\r\nUSN: uuid:0c84::upnp:rootdevice\r\n", "1.1.1.14"),
            Ssdp.parse("ST: upnp:rootdevice\r\nUSN: uuid:0989::upnp:rootdevice\r\n", "1.1.1.14"));
        assert Ssdp.rootUuid(roots, "1.1.1.14").equals("0989");
        java.util.Collections.reverse(roots);
        assert Ssdp.rootUuid(roots, "1.1.1.14").equals("0989");
        // No rootdevice answer: the smallest uuid, so the choice is stable between scans.
        assert Ssdp.rootUuid(hits.subList(0, 1), "1.1.1.9").equals("zzz-embedded");
        assert Ssdp.any(hits, "1.1.1.9", "renderingcontrol");
        assert !Ssdp.any(hits, "1.1.1.8", "webos-second-screen");
    }
}
