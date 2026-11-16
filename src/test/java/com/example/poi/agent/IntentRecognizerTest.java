package com.example.poi.agent;

import com.example.poi.agent.intent.IntentCategory;
import com.example.poi.agent.intent.IntentRecognizer;
import com.example.poi.agent.intent.IntentResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class IntentRecognizerTest {

    private final IntentRecognizer recognizer =
            new IntentRecognizer(mock(LlmAgentClient.class));

    @Test
    void recognizesNearbyAsQueryScene() {
        IntentResult result = recognizer.recognize("附近1公里有哪些医院");

        assertEquals(IntentCategory.QUERY, result.category());
        assertEquals("NEARBY", result.entities().get("scene"));
        assertEquals("医院", result.entities().get("category"));
        assertEquals(1000, result.entities().get("radius"));
    }

    @Test
    void recognizesDistrictStatsAsQueryScene() {
        IntentResult result = recognizer.recognize("黄浦区各类POI有多少");

        assertEquals(IntentCategory.QUERY, result.category());
        assertEquals("DISTRICT_STATS", result.entities().get("scene"));
        assertEquals("黄浦区", result.entities().get("place"));
    }

    @Test
    void recognizesRouteAndRanking() {
        assertEquals(IntentCategory.ROUTE,
                recognizer.recognize("从外滩出发规划一条景点路线").category());

        IntentResult ranking = recognizer.recognize("哪个区POI最多");
        assertEquals(IntentCategory.QUERY, ranking.category());
        assertEquals("RANKING", ranking.entities().get("scene"));
    }

    @Test
    void recognizesBufferStatsAsQueryScene() {
        IntentResult result = recognizer.recognize("外滩附近1公里的餐饮统计");

        assertEquals(IntentCategory.QUERY, result.category());
        assertEquals("BUFFER_STATS", result.entities().get("scene"));
        assertEquals("餐饮", result.entities().get("category"));
    }

    @Test
    void extractsFromAndToForPointToPointRoute() {
        IntentResult result = recognizer.recognize("从外滩到陆家嘴怎么走");

        assertEquals(IntentCategory.ROUTE, result.category());
        assertEquals("外滩", result.entities().get("from"));
        assertEquals("陆家嘴", result.entities().get("to"));
    }

    @Test
    void extractsFromToWithoutLeadingCong() {
        IntentResult result = recognizer.recognize("人民广场到外滩怎么走");

        assertEquals(IntentCategory.ROUTE, result.category());
        assertEquals("人民广场", result.entities().get("from"));
        assertEquals("外滩", result.entities().get("to"));
    }

    @Test
    void extractsFromToWithArrow() {
        IntentResult result = recognizer.recognize("人民广场→外滩怎么走");

        assertEquals(IntentCategory.ROUTE, result.category());
        assertEquals("人民广场", result.entities().get("from"));
        assertEquals("外滩", result.entities().get("to"));
    }
}
