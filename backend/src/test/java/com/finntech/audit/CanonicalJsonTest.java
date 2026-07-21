package com.finntech.audit;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanonicalJsonTest {

    @Test
    void writesStableCanonicalJsonWithSortedKeys() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("z", 1);
        data.put("m", true);
        data.put("a", "x");
        data.put("nested", Map.of("b", 2, "a", 1));

        String actual = CanonicalJson.write(data);

        assertEquals("{\"a\":\"x\",\"m\":true,\"nested\":{\"a\":1,\"b\":2},\"z\":1}", actual);
    }

    @Test
    void escapesSpecialCharactersAndControlChars() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", "a\"b\\c\nd");

        String actual = CanonicalJson.write(data);

        assertEquals("{\"text\":\"a\\\"b\\\\c\\nd\"}", actual);
    }
}
