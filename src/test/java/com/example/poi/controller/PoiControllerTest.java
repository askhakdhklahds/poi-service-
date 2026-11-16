package com.example.poi.controller;

import com.example.poi.security.JwtAuthFilter;
import com.example.poi.security.JwtService;
import com.example.poi.service.PoiService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PoiController.class)
@AutoConfigureMockMvc(addFilters = false)
class PoiControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private PoiService service;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private JwtService jwtService;

    @Test
    void countReturnsTotal() throws Exception {
        given(service.count()).willReturn(3L);

        mvc.perform(get("/api/poi/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(3));
    }

    @Test
    void statsReturnsCategoriesAndSubCategories() throws Exception {
        given(service.stats()).willReturn(Map.of(
                "total", 3L,
                "categories", List.of(Map.of("category", "交通", "cnt", 3L)),
                "subCategories", List.of(
                        Map.of("category", "交通", "sub_category", "公交站", "cnt", 3L))));

        mvc.perform(get("/api/poi/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.subCategories[0].sub_category").value("公交站"));
    }

    @Test
    void searchReturnsPlaces() throws Exception {
        given(service.searchPlaces("人民广场", 8)).willReturn(List.of(
                Map.of("name", "人民广场", "category", "交通",
                        "lon", 121.4695, "lat", 31.2292)));

        mvc.perform(get("/api/poi/search").param("keyword", "人民广场"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("人民广场"))
                .andExpect(jsonPath("$.data[0].lon").value(121.4695));
    }

    @Test
    void searchReturnsEmptyForBlankKeyword() throws Exception {
        mvc.perform(get("/api/poi/search").param("keyword", " "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void detailReturnsPoi() throws Exception {
        given(service.detail(1)).willReturn(Map.of("id", 1, "name", "测试点"));

        mvc.perform(get("/api/poi/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("测试点"));
    }

    @Test
    void detailReturns404WhenMissing() throws Exception {
        given(service.detail(99)).willReturn(Map.of());

        mvc.perform(get("/api/poi/99"))
                .andExpect(status().isNotFound());
    }
}
