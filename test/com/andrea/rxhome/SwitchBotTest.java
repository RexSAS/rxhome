package com.andrea.rxhome;

import java.io.IOException;
import java.util.Map;

public class SwitchBotTest {
    public static void main(String[] a) throws Exception {
        // Reference: python3 -c "import hmac,hashlib,base64;print(base64.b64encode(
        //   hmac.new(b'sec',b'tok1700000000000n1',hashlib.sha256).digest()).decode())"
        assert SwitchBot.sign("tok", "sec", 1700000000000L, "n1").equals("hLOA/UgsG10H6PntCjjrIE5/WFcDcIFma2xQ3e8W9mA=");

        SwitchBot.check("{\"statusCode\":100,\"body\":{}}");
        try {
            SwitchBot.check("{\"statusCode\":190,\"message\":\"wrong deviceId\"}");
            throw new AssertionError("190 accepted");
        } catch (IOException e) {
            assert e.getMessage().equals("wrong deviceId") : e.getMessage();
        }

        Map<String, String> d = SwitchBot.parseDevices("{\"statusCode\":100,\"body\":{"
            + "\"deviceList\":[{\"deviceId\":\"B1\",\"deviceName\":\"Server button\",\"deviceType\":\"Bot\"}],"
            + "\"infraredRemoteList\":[{\"deviceId\":\"02-1\",\"deviceName\":\"Philips\",\"remoteType\":\"TV\"}]}}");
        assert d.get("B1").equals("Server button");
        assert d.get("02-1").equals("Philips");
    }
}
