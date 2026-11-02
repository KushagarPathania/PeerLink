package com.peerlink.agent.networking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FlatJsonTests {

    @Test
    void parsesFlatObjects() {
        Map<String, String> json = FlatJson.parseObject(
                " {\"a\":\"x\\\"y\\u0041\",\"n\":-12.5e3,\"t\":true,\"z\":null,\"port\":9090} ");

        assertThat(json).containsEntry("a", "x\"yA").containsEntry("n", "-12.5e3").containsEntry("t", "true")
                .containsEntry("port", "9090").containsKey("z");
        assertThat(json.get("z")).isNull();
        assertThat(FlatJson.parseObject("{}")).isEmpty();
    }

    @Test
    void rejectsNestingDuplicatesAndGarbage() {
        assertThatIllegalArgumentException().isThrownBy(() -> FlatJson.parseObject("{\"a\":{\"b\":1}}"));
        assertThatIllegalArgumentException().isThrownBy(() -> FlatJson.parseObject("{\"a\":[1]}"));
        assertThatIllegalArgumentException().isThrownBy(() -> FlatJson.parseObject("{\"a\":1,\"a\":2}"));
        assertThatIllegalArgumentException().isThrownBy(() -> FlatJson.parseObject("{\"a\":1} extra"));
        assertThatIllegalArgumentException().isThrownBy(() -> FlatJson.parseObject("{\"a\":01}"));
        assertThatIllegalArgumentException().isThrownBy(() -> FlatJson.parseObject("{\"a\":\"unterminated}"));
        assertThatIllegalArgumentException().isThrownBy(() -> FlatJson.parseObject("{\"a\":tru}"));
        assertThatIllegalArgumentException().isThrownBy(() -> FlatJson.parseObject("x".repeat(FlatJson.MAX_INPUT_LENGTH + 1)));
    }

    @Test
    void writesEscapedObjects() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("ticket", "a\"b\\c\n");
        fields.put("port", 9090);

        String json = FlatJson.writeObject(fields);

        assertThat(json).isEqualTo("{\"ticket\":\"a\\\"b\\\\c\\u000a\",\"port\":9090}");
        assertThat(FlatJson.parseObject(json)).containsEntry("ticket", "a\"b\\c\n").containsEntry("port", "9090");
    }
}
