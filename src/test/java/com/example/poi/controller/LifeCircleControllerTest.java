package com.example.poi.controller;

import com.example.poi.security.JwtAuthFilter;
import com.example.poi.security.JwtService;
import com.example.poi.service.life_circle.CoverageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LifeCircleController.class)
@AutoConfigureMockMvc(addFilters = false)
class LifeCircleControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockBean
    private CoverageService coverageService;

    @MockBean
    private JwtAuthFilter jwtAuthFilter;

    @MockBean
    private JwtService jwtService;

    @Test
    void categoriesReturnsStandardList() throws Exception {
        given(coverageService.categories()).willReturn(List.of(
                Map.of("code", "healthcare", "name_zh", "医疗", "poi_category", "医院")));

        mvc.perform(get("/api/life-circle/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("healthcare"))
                .andExpect(jsonPath("$.data[0].name_zh").value("医疗"));
    }

    @Test
    void coverageReturnsSummary() throws Exception {
        given(coverageService.coverage("310101", "healthcare", 900)).willReturn(Map.of(
                "districtName", "黄浦区",
                "categoryName", "医疗",
                "popTotal", 500000,
                "popCovered", 400000,
                "coverageRatio", 0.8,
                "gapPopulation", 100000));

        mvc.perform(get("/api/life-circle/coverage")
                        .param("adcode", "310101")
                        .param("category", "healthcare")
                        .param("threshold", "900"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.districtName").value("黄浦区"))
                .andExpect(jsonPath("$.data.coverageRatio").value(0.8));
    }

    @Test
    void coverageRejectsUnknownCategory() throws Exception {
        given(coverageService.coverage(anyString(), eq("nope"), eq(900)))
                .willThrow(new IllegalArgumentException("unknown category: nope"));

        mvc.perform(get("/api/life-circle/coverage")
                        .param("adcode", "310101")
                        .param("category", "nope")
                        .param("threshold", "900"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void coverageRequiresAdcode() throws Exception {
        mvc.perform(get("/api/life-circle/coverage")
                        .param("category", "healthcare"))
                .andExpect(status().isBadRequest());
    }
}
