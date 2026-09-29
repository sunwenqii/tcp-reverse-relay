package dev.sunwenqi.tcpreverserelay.channel;

import com.alibaba.fastjson.JSONObject;
import dev.sunwenqi.tcpreverserelay.model.InteractiveModel;
import dev.sunwenqi.tcpreverserelay.model.SecretInteractiveModel;
import dev.sunwenqi.tcpreverserelay.utils.AESUtil;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.security.Key;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SecretInteractiveChannelTest {
    @Test
    void rejectsReplayOfAnAuthenticatedMessage() throws Exception {
        Key key = AESUtil.createKey(128);
        String token = "test-token-with-more-than-thirty-two-characters";
        SecretInteractiveModel message = new SecretInteractiveModel(
                new InteractiveModel("seq", "test", new JSONObject()));
        message.fullMessage(key, token);

        for (int attempt = 0; attempt < 2; attempt++) {
            try (ServerSocket server = new ServerSocket(0);
                 Socket sender = new Socket("127.0.0.1", server.getLocalPort());
                 Socket receiver = server.accept()) {
                new StringChannel(sender).writeAndFlush(message.toJSONString());
                SecretInteractiveChannel channel = new SecretInteractiveChannel(receiver);
                channel.setAesKey(key);
                channel.setTokenKey(token);
                if (attempt == 0) {
                    assertEquals("seq", channel.read().getInteractiveSeq());
                } else {
                    assertThrows(IllegalStateException.class, channel::read);
                }
            }
        }
    }
}
